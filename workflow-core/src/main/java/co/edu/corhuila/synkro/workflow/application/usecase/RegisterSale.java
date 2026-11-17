package co.edu.corhuila.synkro.workflow.application.usecase;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Action;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Result;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Step;
import java.time.Clock;
import java.time.Instant;
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
    private final AuditTrail audit;
    private final DeferralSchedule deferrals;
    public RegisterSale(SagaStore store,ExecutionCoordinator coordination,SaleParticipants participants,
                        Clock clock,Identifiers ids,RetryDelay delay,int maxAttempts) {
        this(store,coordination,participants,clock,ids,delay,maxAttempts,AuditTrail.NONE);
    }
    public RegisterSale(SagaStore store,ExecutionCoordinator coordination,SaleParticipants participants,
                        Clock clock,Identifiers ids,RetryDelay delay,int maxAttempts,AuditTrail audit) {
        this(store,coordination,participants,clock,ids,delay,maxAttempts,audit,DeferralSchedule.standard());
    }
    public RegisterSale(SagaStore store,ExecutionCoordinator coordination,SaleParticipants participants,
                        Clock clock,Identifiers ids,RetryDelay delay,int maxAttempts,AuditTrail audit,DeferralSchedule deferrals) {
        this.store=store;this.coordination=coordination;this.participants=participants;
        this.clock=clock;this.ids=ids;this.delay=delay;this.maxAttempts=maxAttempts;this.audit=Objects.requireNonNull(audit);
        this.deferrals=Objects.requireNonNull(deferrals);
        if(maxAttempts<1 || maxAttempts>10) throw new IllegalArgumentException("Invalid attempt bound");
    }
    public SagaStore.Created start(SaleInput input,UUID caller,String key,String trace) {
        var created=store.create(Saga.create(ids.next(),key,caller,input,trace,clock.instant()));
        Saga saga=created.saga();
        if(!saga.initiatedBy().equals(caller)) {
            auditStart(saga,caller,trace,Result.FORBIDDEN);
            throw new WorkflowError("FORBIDDEN");
        }
        if(!saga.input().equals(input)) {
            auditStart(saga,caller,trace,Result.CONFLICT);
            throw new WorkflowError("VALIDATION_ERROR");
        }
        auditStart(saga,caller,trace,created.created()?Result.CREATED:Result.REPLAYED);
        return created;
    }
    public void execute(UUID id) {
        var owned=coordination.tryOwn(id);if(owned.isEmpty()) return;
        try(var lease=owned.get()) {
            Saga saga=lease.load();
            if(saga==null || saga.status()!=Saga.Status.RUNNING || saga.requiresOperator()) return;
            if(saga.isDeferred()) {
                if(!saga.isRetryDue(clock.instant())) return;
                saga=saga.resumeDeferred(clock.instant());lease.save(saga);
            }
            if(!saga.completedSteps().isEmpty() || saga.execution().pendingStep()!=null || saga.failedStep()!=null || saga.execution().attempts()>0)
                auditSaga(saga,null,Action.RESUME_SAGA,Result.RESUMED);
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
                        saga=saga.success(step,reserved,sale,clock.instant());lease.save(saga);success=true;
                        auditSaga(saga,step,Action.EXECUTE_STEP,Result.SUCCEEDED);
                        if(saga.status()==Saga.Status.COMPLETED) auditSaga(saga,null,Action.FINISH_SAGA,Result.COMPLETED);
                        break;
                    } catch(ParticipantFailure failure) {
                        if(failure.kind()==ParticipantFailure.Kind.REJECTED && (!ambiguous || step.equals("validate-customer"))) {
                            saga=saga.reject(step,clock.instant());lease.save(saga);
                            auditSaga(saga,step,Action.EXECUTE_STEP,Result.REJECTED);
                            break;
                        }
                        ambiguous=true;
                        if(failure.kind()==ParticipantFailure.Kind.CIRCUIT_OPEN) { deferOrEscalate(lease,saga,step);return; }
                        if(failure.kind()!=ParticipantFailure.Kind.TRANSIENT) { escalate(lease,saga,step);return; }
                        auditSaga(saga,step,Action.EXECUTE_STEP,Result.TRANSIENT_FAILURE);
                        if(saga.execution().attempts()<maxAttempts) delay.pause(saga.execution().attempts());
                    }
                }
                if(!success && saga.failedStep()==null) { deferOrEscalate(lease,saga,step);return; }
            }
        }
    }
    /** An unavailable participant is retried forward later with the same step key; never compensated blindly. */
    private void deferOrEscalate(ExecutionCoordinator.Lease lease,Saga saga,String step) {
        var wait=deferrals.waitAfter(saga.execution().deferrals());
        if(wait.isEmpty()) { escalate(lease,saga,step);return; }
        Instant now=clock.instant();
        lease.save(saga.defer(now.plus(wait.get()),now));
        auditSaga(saga,step,Action.EXECUTE_STEP,Result.DEFERRED);
    }
    private void escalate(ExecutionCoordinator.Lease lease,Saga saga,String step) {
        lease.save(saga.unresolved(clock.instant()));
        auditSaga(saga,step,Action.EXECUTE_STEP,Result.UNRESOLVED);
    }
    private void compensate(ExecutionCoordinator.Lease lease,Saga saga) {
        if(saga.reservation()==null) {
            lease.save(saga.finishCompensation(true,clock.instant()));
            auditSaga(saga,null,Action.FINISH_SAGA,Result.COMPENSATED);
            return;
        }
        while(saga.execution().attempts()<maxAttempts) {
            saga=saga.attempt("release-stock",clock.instant());lease.save(saga);
            try {
                participants.release(saga.reservation(),saga.id()+":release-stock",saga.execution().correlationId());
                lease.save(saga.finishCompensation(true,clock.instant()));
                auditSaga(saga,"release-stock",Action.COMPENSATE,Result.SUCCEEDED);
                auditSaga(saga,null,Action.FINISH_SAGA,Result.COMPENSATED);
                return;
            } catch(ParticipantFailure failure) {
                auditSaga(saga,"release-stock",Action.COMPENSATE,failure.kind()==ParticipantFailure.Kind.TRANSIENT?Result.TRANSIENT_FAILURE:Result.FAILED);
                if(failure.kind()!=ParticipantFailure.Kind.TRANSIENT) break;
                if(saga.execution().attempts()<maxAttempts) delay.pause(saga.execution().attempts());
            }
        }
        lease.save(saga.finishCompensation(false,clock.instant()));
        auditSaga(saga,null,Action.FINISH_SAGA,Result.FAILED);
    }
    private void auditStart(Saga saga,UUID caller,String trace,Result result) {
        emit(new AuditEvent(clock.instant(),AuditEvent.traceValue(trace),saga.id(),null,caller,Action.START_SAGA,result));
    }
    /** Audited with the trace persisted on the saga, so background recovery stays on the original request's trace. */
    private void auditSaga(Saga saga,String step,Action action,Result result) {
        emit(new AuditEvent(clock.instant(),AuditEvent.traceValue(saga.execution().correlationId()),saga.id(),
            step==null?null:Step.of(step).orElse(null),saga.initiatedBy(),action,result));
    }
    /** An audit failure must never change the outcome of the saga. */
    private void emit(AuditEvent event) {
        try { audit.record(event); } catch(RuntimeException ignored) { }
    }
}
