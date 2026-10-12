package co.edu.corhuila.synkro.workflow.application.usecase;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import java.time.Clock;
import java.util.*;

/** The three owning operations, without transport, database or framework dependencies. */
public final class RegisterSale {
    private final SagaStore store;
    private final ExecutionCoordinator coordination;
    private final SaleParticipants participants;
    private final Clock clock;
    private final Identifiers ids;
    private final RetryDelay delay;
    private final int maxAttempts;
    public RegisterSale(SagaStore store,ExecutionCoordinator coordination,SaleParticipants participants,
                        Clock clock,Identifiers ids,RetryDelay delay,int maxAttempts) {
        this.store=store;this.coordination=coordination;this.participants=participants;
        this.clock=clock;this.ids=ids;this.delay=delay;this.maxAttempts=maxAttempts;
        if(maxAttempts<1 || maxAttempts>10) throw new IllegalArgumentException("Invalid attempt bound");
    }
    public SagaStore.Created start(SaleInput input,UUID caller,String key,String trace) {
        var created=store.create(Saga.create(ids.next(),key,caller,input,trace,clock.instant()));
        if(!created.saga().initiatedBy().equals(caller)) throw new WorkflowError("FORBIDDEN");
        if(!created.saga().input().equals(input)) throw new WorkflowError("VALIDATION_ERROR");
        return created;
    }
    public void execute(UUID id) {
        var owned=coordination.tryOwn(id);if(owned.isEmpty()) return;
        try(var lease=owned.get()) {
            Saga saga=lease.load();
            if(saga==null || saga.status()!=Saga.Status.RUNNING) return;
            while(saga.status()==Saga.Status.RUNNING) {
                if(Thread.currentThread().isInterrupted()) return;
                if(saga.failedStep()!=null) { compensate(lease,saga);return; }
                String step=saga.completedSteps().isEmpty()?"validate-customer":
                    saga.completedSteps().size()==1?"reserve-stock":"register-sale";
                boolean ambiguous=saga.execution().pendingStep()!=null;
                boolean success=false;
                while(saga.execution().attempts()<maxAttempts) {
                    saga=saga.attempt(step,clock.instant());lease.save(saga);
                    try {
                        String key=saga.id()+":"+step,trace=saga.execution().correlationId();
                        Reservation reserved=saga.reservation();UUID sale=null;
                        switch(step) {
                            case "validate-customer" -> participants.validateCustomer(saga.input().customerId(),key,trace);
                            case "reserve-stock" -> reserved=participants.reserve(saga.input(),key,trace);
                            case "register-sale" -> sale=participants.register(saga,key,trace);
                            default -> throw new WorkflowError("INTERNAL_ERROR");
                        }
                        saga=saga.success(step,reserved,sale,clock.instant());lease.save(saga);success=true;break;
                    } catch(ParticipantFailure failure) {
                        if(failure.kind()==ParticipantFailure.Kind.REJECTED && (!ambiguous || step.equals("validate-customer"))) {
                            saga=saga.reject(step,clock.instant());lease.save(saga);break;
                        }
                        ambiguous=true;
                        if(failure.kind()!=ParticipantFailure.Kind.TRANSIENT) {
                            lease.save(saga.unresolved(clock.instant()));return;
                        }
                        if(saga.execution().attempts()<maxAttempts) delay.pause(saga.execution().attempts());
                    }
                }
                if(!success && saga.failedStep()==null) { lease.save(saga.unresolved(clock.instant()));return; }
            }
        }
    }
    private void compensate(ExecutionCoordinator.Lease lease,Saga saga) {
        if(saga.reservation()==null) { lease.save(saga.finishCompensation(true,clock.instant()));return; }
        while(saga.execution().attempts()<maxAttempts) {
            saga=saga.attempt("release-stock",clock.instant());lease.save(saga);
            try {
                participants.release(saga.reservation(),saga.id()+":release-stock",saga.execution().correlationId());
                lease.save(saga.finishCompensation(true,clock.instant()));return;
            } catch(ParticipantFailure failure) {
                if(failure.kind()!=ParticipantFailure.Kind.TRANSIENT) break;
                if(saga.execution().attempts()<maxAttempts) delay.pause(saga.execution().attempts());
            }
        }
        lease.save(saga.finishCompensation(false,clock.instant()));
    }
}
