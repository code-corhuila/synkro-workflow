package co.edu.corhuila.synkro.workflow;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class RegisterSaleTest {
    final UUID customer = UUID.randomUUID(), product = UUID.randomUUID(), caller = UUID.randomUUID();
    final SaleInput input = new SaleInput(customer, List.of(new SaleInput.Line(product, 2)));
    final Clock clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);
    final Memory store = new Memory();
    final People participants = new People();
    final RegisterSale usecase = new RegisterSale(store, store, participants, clock, UUID::randomUUID, millis -> {}, 3);
    Saga start() { return usecase.start(input, caller, "key-12345", "correlation").saga(); }

    @Test void normalExecutionUsesFrozenPricesAndInitiator() {
        Saga saga = start(); usecase.execute(saga.id());
        Saga done = store.find(saga.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(Saga.Status.COMPLETED);
        assertThat(done.completedSteps()).containsExactly("validate-customer", "reserve-stock", "register-sale");
        assertThat(done.saleId()).isEqualTo(participants.sale);
        assertThat(participants.registered.initiatedBy()).isEqualTo(caller);
        assertThat(participants.registered.reservation().lines().getFirst().unitPriceCents()).isEqualTo(125);
        assertThat(participants.keys).contains(saga.id()+":reserve-stock", saga.id()+":register-sale");
        assertThat(participants.correlations).containsOnly("correlation");
    }
    @Test void equivalentReplayAndConflictsDoNotMutateOrCallParticipants() {
        Saga first = start();
        assertThat(usecase.start(input, caller, "key-12345", "different").created()).isFalse();
        assertThat(store.find(first.id())).contains(first);
        assertThatThrownBy(() -> usecase.start(input, UUID.randomUUID(), "key-12345", "different"))
            .isInstanceOf(WorkflowError.class).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> usecase.start(new SaleInput(customer, List.of(new SaleInput.Line(product, 3))), caller, "key-12345", "different"))
            .isInstanceOf(WorkflowError.class).hasMessage("VALIDATION_ERROR");
        assertThat(store.find(first.id())).contains(first);
        assertThat(participants.keys).isEmpty();
    }
    @Test void rejectsInvalidInputAndNormalizesOrder() {
        UUID second = UUID.randomUUID();
        assertThat(new SaleInput(customer, List.of(new SaleInput.Line(product, 2), new SaleInput.Line(second, 1))))
            .isEqualTo(new SaleInput(customer, List.of(new SaleInput.Line(second, 1), new SaleInput.Line(product, 2))));
        assertThatThrownBy(() -> new SaleInput.Line(product, 0)).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> new SaleInput.Line(null, 1)).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> new SaleInput(null, input.lines())).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> new SaleInput(customer, List.of())).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> new SaleInput(customer, Collections.nCopies(101, input.lines().getFirst()))).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> new SaleInput(customer, Collections.nCopies(2, input.lines().getFirst()))).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> usecase.start(input, caller, "short", "trace")).isInstanceOf(WorkflowError.class);
    }
    @Test void concurrentConflictingReplaysPreserveTheResourceAndNeverInvokeParticipants() throws Exception {
        Saga first=start(); UUID other=UUID.randomUUID();
        SaleInput conflict=new SaleInput(customer,List.of(new SaleInput.Line(product,3)));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var gate=new CyclicBarrier(8);var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++) {
                boolean differentCaller=i%2==0;
                tasks.add(pool.submit(()->{
                    gate.await();
                    try {usecase.start(differentCaller?input:conflict,differentCaller?other:caller,"key-12345","new-correlation");return "unexpected success";}
                    catch(WorkflowError error){return error.getMessage();}
                }));
            }
            var errors=new ArrayList<String>();for(var task:tasks)errors.add(task.get(5,TimeUnit.SECONDS));
            assertThat(errors).containsOnly("FORBIDDEN","VALIDATION_ERROR");
            assertThat(errors.stream().filter("FORBIDDEN"::equals).count()).isEqualTo(4);
        }
        assertThat(store.rows).hasSize(1);assertThat(store.find(first.id())).contains(first);
        assertThat(participants.keys).isEmpty();
    }
    @Test void rejectionBeforeReservationHasNoEffects() {
        participants.reject = "validate-customer";
        Saga saga = start(); usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPENSATED);
        assertThat(participants.reservations).isZero(); assertThat(participants.sales).isZero();
        assertThat(participants.releases).isZero();
    }
    @Test void reservationRejectionDoesNotRegisterOrRelease() {
        participants.reject = "reserve-stock";
        Saga saga = start(); usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().failedStep()).isEqualTo("reserve-stock");
        assertThat(participants.sales).isZero(); assertThat(participants.releases).isZero();
    }
    @Test void confirmedSaleRejectionCompensatesWithOriginalFailedStep() {
        participants.reject = "register-sale";
        Saga saga = start(); usecase.execute(saga.id()); usecase.execute(saga.id());
        Saga result = store.find(saga.id()).orElseThrow();
        assertThat(result.status()).isEqualTo(Saga.Status.COMPENSATED);
        assertThat(result.failedStep()).isEqualTo("register-sale");
        assertThat(participants.releases).isEqualTo(1);
    }
    @Test void exhaustedCompensationIsFailedAndNeverAutoResumed() {
        participants.reject = "register-sale"; participants.releaseFailure = true;
        Saga saga = start(); usecase.execute(saga.id()); usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.FAILED);
        assertThat(participants.releaseCalls).isEqualTo(3);
        assertThat(store.find(saga.id()).orElseThrow().failedStep()).isEqualTo("register-sale");
    }
    @Test void lostCommittedSaleResponseReplaysWithoutReleasingStock() {
        participants.loseSaleResponse = true;
        Saga saga = start(); usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPLETED);
        assertThat(participants.sales).isEqualTo(1); assertThat(participants.releases).isZero();
    }
    @Test void unresolvedSaleThenBusinessRejectionCannotReleaseStock() {
        participants.loseSaleResponse = true; participants.rejectAfterLost = true;
        Saga saga = start(); usecase.execute(saga.id()); usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.RUNNING);
        assertThat(store.find(saga.id()).orElseThrow().errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");
        assertThat(participants.releases).isZero();
    }
    @Test void resultSaveFailureStopsNextStepAndRestartReplaysCommittedReservation() {
        store.failWhenStep = "reserve-stock";
        Saga saga = start(); assertThatThrownBy(() -> usecase.execute(saga.id())).isInstanceOf(WorkflowError.class);
        assertThat(participants.sales).isZero(); assertThat(participants.reservations).isEqualTo(1);
        store.failWhenStep = null; usecase.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPLETED);
        assertThat(participants.reservations).isEqualTo(1);
    }
    @Test void resultSaveFailureAfterSaleDoesNotCompensate() {
        store.failWhenStep = "register-sale";
        Saga saga = start(); assertThatThrownBy(() -> usecase.execute(saga.id())).isInstanceOf(WorkflowError.class);
        assertThat(participants.releases).isZero(); store.failWhenStep = null; usecase.execute(saga.id());
        assertThat(participants.sales).isEqualTo(1);
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPLETED);
    }
    @Test void compensationResultSaveFailureRecoversRepeatably() {
        participants.reject = "register-sale"; store.failStatus = Saga.Status.COMPENSATED;
        Saga saga = start(); assertThatThrownBy(() -> usecase.execute(saga.id())).isInstanceOf(WorkflowError.class);
        assertThat(store.find(saga.id()).orElseThrow().failedStep()).isEqualTo("register-sale");
        store.failStatus = null; usecase.execute(saga.id());
        assertThat(participants.releases).isEqualTo(1);
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPENSATED);
    }
    @Test void noStoreCreationMeansNoEffect() {
        store.failCreate = true;
        assertThatThrownBy(this::start).isInstanceOf(WorkflowError.class);
        assertThat(participants.keys).isEmpty();
    }
    @Test void simultaneousStartsAndExecutorsConverge() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CyclicBarrier(8); var tasks = new ArrayList<Future<UUID>>();
            for (int i=0;i<8;i++) tasks.add(pool.submit(() -> { gate.await(); Saga s=start(); usecase.execute(s.id()); return s.id(); }));
            Set<UUID> ids = new HashSet<>(); for (var task:tasks) ids.add(task.get(5, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1); usecase.execute(ids.iterator().next());
            assertThat(participants.reservations).isEqualTo(1); assertThat(participants.sales).isEqualTo(1);
        }
    }
    @Test void persistedAmbiguousIntentSurvivesNewUsecaseInstance() {
        Saga saga = start(); participants.transientReservation = true; usecase.execute(saga.id());
        Saga pending = store.find(saga.id()).orElseThrow();
        assertThat(pending.status()).isEqualTo(Saga.Status.RUNNING);
        assertThat(pending.execution().pendingStep()).isEqualTo("reserve-stock");
        int calls = participants.reserveCalls;
        new RegisterSale(store, store, participants, clock, UUID::randomUUID, millis -> {}, 3).execute(saga.id());
        assertThat(participants.reserveCalls).isEqualTo(calls);
    }

    static final class Memory implements SagaStore, ExecutionCoordinator {
        final ConcurrentMap<UUID,Saga> rows=new ConcurrentHashMap<>();
        final ConcurrentMap<UUID,Semaphore> locks=new ConcurrentHashMap<>();
        String failWhenStep; Saga.Status failStatus; boolean failCreate;
        public synchronized Created create(Saga saga) {
            if(failCreate) throw new WorkflowError("INTERNAL_ERROR");
            Saga prior=rows.values().stream().filter(s -> s.idempotencyKey().equals(saga.idempotencyKey())).findFirst().orElse(null);
            if(prior!=null) return new Created(prior,false); rows.put(saga.id(),saga); return new Created(saga,true);
        }
        public Optional<Saga> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public List<UUID> running(int limit) { return rows.values().stream().filter(s->s.status()==Saga.Status.RUNNING).limit(limit).map(Saga::id).toList(); }
        public boolean healthy() { return true; }
        public Optional<Lease> tryOwn(UUID id) {
            Semaphore lock=locks.computeIfAbsent(id,k->new Semaphore(1)); if(!lock.tryAcquire()) return Optional.empty();
            return Optional.of(new Lease() {
                public Saga load() { return rows.get(id); }
                public void save(Saga saga) {
                    if((failWhenStep!=null && saga.completedSteps().contains(failWhenStep)) || saga.status()==failStatus) throw new WorkflowError("INTERNAL_ERROR");
                    rows.put(id,saga);
                }
                public void close() { lock.release(); }
            });
        }
    }
    final class People implements SaleParticipants {
        final UUID reservation=UUID.randomUUID(), sale=UUID.randomUUID();
        final List<String> keys=new ArrayList<>(),correlations=new ArrayList<>();
        int reservations,sales,releases,reserveCalls,releaseCalls;
        String reject; boolean releaseFailure,loseSaleResponse,rejectAfterLost,transientReservation;
        Saga registered;
        void check(String step,String key,String trace) {
            keys.add(key);correlations.add(trace);
            if(step.equals(reject)) throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED);
        }
        public void validateCustomer(UUID id,String key,String trace) { check("validate-customer",key,trace); }
        public Reservation reserve(SaleInput command,String key,String trace) {
            check("reserve-stock",key,trace); reserveCalls++;
            if(transientReservation) throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);
            reservations=1; return new Reservation(reservation,List.of(new Reservation.Line(product,2,125)));
        }
        public UUID register(Saga saga,String key,String trace) {
            check("register-sale",key,trace);registered=saga;
            if(sales==1 && rejectAfterLost) throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED);
            sales=1;
            if(loseSaleResponse) { loseSaleResponse=false; throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT); }
            return sale;
        }
        public void release(Reservation r,String key,String trace) {
            check("release-stock",key,trace);releaseCalls++;
            if(releaseFailure) throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT); releases=1;
        }
    }
}
