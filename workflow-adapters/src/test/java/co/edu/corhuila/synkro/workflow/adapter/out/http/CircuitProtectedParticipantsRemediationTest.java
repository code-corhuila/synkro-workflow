package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import co.edu.corhuila.synkro.workflow.domain.model.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Decorator of the participants port, driven only through the port and through the use case. */
class CircuitProtectedParticipantsRemediationTest {
    enum Op {CUSTOMERS,RESERVE,SALES,RELEASE}
    static final Duration COOLDOWN=Duration.ofSeconds(15);
    final UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),reservationId=UUID.randomUUID(),sale=UUID.randomUUID(),caller=UUID.randomUUID();
    final SaleInput input=new SaleInput(customer,List.of(new SaleInput.Line(product,2)));
    final Reservation reservation=new Reservation(reservationId,List.of(new Reservation.Line(product,2,125)));
    final ManualClock clock=new ManualClock();
    final Scripted delegate=new Scripted();
    final CircuitProtectedParticipants guarded=new CircuitProtectedParticipants(delegate,clock);

    static final class ManualClock extends Clock {
        volatile Instant now=Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(Duration d){now=now.plus(d);}
    }
    /** Scripted participants: each operation fails with the configured throwable or succeeds with fixed values. */
    final class Scripted implements SaleParticipants {
        final Map<Op,AtomicInteger> calls=new EnumMap<>(Op.class);final Map<Op,Throwable> failure=new EnumMap<>(Op.class);
        Object[] lastArguments;
        Scripted(){for(Op op:Op.values())calls.put(op,new AtomicInteger());}
        int calls(Op op){return calls.get(op).get();}
        void enter(Op op,Object... arguments) {
            calls.get(op).incrementAndGet();lastArguments=arguments;
            Throwable t=failure.get(op);
            if(t instanceof Error e)throw e;
            if(t instanceof RuntimeException e)throw e;
        }
        public void validateCustomer(UUID id,String key,String correlation){enter(Op.CUSTOMERS,id,key,correlation);}
        public Reservation reserve(SaleInput in,String key,String correlation){enter(Op.RESERVE,in,key,correlation);return reservation;}
        public UUID register(Saga saga,String key,String correlation){enter(Op.SALES,saga,key,correlation);return sale;}
        public void release(Reservation r,String key,String correlation){enter(Op.RELEASE,r,key,correlation);}
    }
    Saga reservedSaga(){return Saga.create(UUID.randomUUID(),"port-regression",caller,input,"trace",Instant.EPOCH)
        .success("validate-customer",null,null,Instant.EPOCH).success("reserve-stock",reservation,null,Instant.EPOCH);}
    Saga freshSaga(){return Saga.create(UUID.randomUUID(),"port-regression",caller,input,"trace",Instant.EPOCH);}

    Throwable invoke(Op op) {
        return catchThrowable(()->{
            switch(op) {
                case CUSTOMERS -> guarded.validateCustomer(customer,"key","trace");
                case RESERVE -> guarded.reserve(input,"key","trace");
                case SALES -> guarded.register(reservedSaga(),"key","trace");
                case RELEASE -> guarded.release(reservation,"key","trace");
            }
        });
    }
    void script(Op op,Throwable t){if(t==null)delegate.failure.remove(op);else delegate.failure.put(op,t);}
    static ParticipantFailure transientFailure(){return new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);}
    void outage(Op op,int times){script(op,transientFailure());for(int i=0;i<times;i++)assertThat(invoke(op)).isInstanceOf(ParticipantFailure.class);script(op,null);}
    void assertRefused(Op op) {
        int before=delegate.calls(op);
        assertThat(invoke(op)).isInstanceOfSatisfying(ParticipantFailure.class,e->assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN));
        assertThat(delegate.calls(op)).as("refused call must not reach the participant").isEqualTo(before);
    }
    void assertDelegated(Op op) {
        int before=delegate.calls(op);
        assertThat(invoke(op)).isNull();
        assertThat(delegate.calls(op)).isEqualTo(before+1);
    }
    void openProducts(){outage(Op.RESERVE,3);assertRefused(Op.RESERVE);}

    // ---------- dispatch and isolation ----------
    @Test void healthyCallsPassTheOriginalArgumentsAndReceiptsThrough() {
        var in=input;
        assertThat(guarded.reserve(in,"saga:reserve-stock","trace-1")).isSameAs(reservation);
        assertThat(delegate.lastArguments).containsExactly(in,"saga:reserve-stock","trace-1");
        var saga=reservedSaga();
        assertThat(guarded.register(saga,"saga:register-sale","trace-2")).isEqualTo(sale);
        assertThat(delegate.lastArguments).containsExactly(saga,"saga:register-sale","trace-2");
        guarded.validateCustomer(customer,"saga:validate-customer","trace-3");
        assertThat(delegate.lastArguments).containsExactly(customer,"saga:validate-customer","trace-3");
        guarded.release(reservation,"saga:release-stock","trace-4");
        assertThat(delegate.lastArguments).containsExactly(reservation,"saga:release-stock","trace-4");
    }
    @Test void participantFailuresPropagateUnchangedWhileTheCircuitIsClosed() {
        var rejected=new ParticipantFailure(ParticipantFailure.Kind.REJECTED);script(Op.CUSTOMERS,rejected);
        assertThat(invoke(Op.CUSTOMERS)).isSameAs(rejected);
        var transientFailure=transientFailure();script(Op.SALES,transientFailure);
        assertThat(invoke(Op.SALES)).isSameAs(transientFailure);
    }
    @Test void customersOutageDoesNotAffectProductsOrSales() {
        outage(Op.CUSTOMERS,3);assertRefused(Op.CUSTOMERS);
        assertDelegated(Op.RESERVE);assertDelegated(Op.RELEASE);assertDelegated(Op.SALES);
    }
    @Test void salesOutageDoesNotAffectCustomersOrProducts() {
        outage(Op.SALES,3);assertRefused(Op.SALES);
        assertDelegated(Op.CUSTOMERS);assertDelegated(Op.RESERVE);assertDelegated(Op.RELEASE);
    }
    @Test void reserveAndReleaseShareOneProductsCircuit() {
        outage(Op.RESERVE,3);
        assertRefused(Op.RESERVE);assertRefused(Op.RELEASE);
        assertDelegated(Op.CUSTOMERS);assertDelegated(Op.SALES);
    }
    @Test void releaseFailuresAlsoOpenTheSharedProductsCircuit() {
        outage(Op.RELEASE,2);outage(Op.RESERVE,1); // two release + one reserve outages are three consecutive Products failures
        assertRefused(Op.RESERVE);assertRefused(Op.RELEASE);
    }
    @Test void circuitOpenIsAFixedPrivateFailureWithoutDriverText() {
        openProducts();
        var failure=(ParticipantFailure)invoke(Op.RESERVE);
        assertThat(failure.kind()).isEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN).isNotEqualTo(ParticipantFailure.Kind.REJECTED);
        assertThat(failure).hasMessage("CIRCUIT_OPEN").hasNoCause();
    }

    // ---------- classification ----------
    @Test void onlyTransientFailuresCountAsOutage() {
        for(var kind:List.of(ParticipantFailure.Kind.REJECTED,ParticipantFailure.Kind.AUTHORIZATION,ParticipantFailure.Kind.INVALID_RESPONSE,ParticipantFailure.Kind.LOCAL_FAILURE)) {
            script(Op.SALES,new ParticipantFailure(kind));
            for(int i=0;i<10;i++)assertThat(invoke(Op.SALES)).isInstanceOf(ParticipantFailure.class);
            script(Op.SALES,null);assertDelegated(Op.SALES);
        }
    }
    @Test void neutralFailuresNeitherCountNorResetAPartialOutage() {
        script(Op.CUSTOMERS,transientFailure());invoke(Op.CUSTOMERS);invoke(Op.CUSTOMERS);
        script(Op.CUSTOMERS,new ParticipantFailure(ParticipantFailure.Kind.REJECTED));invoke(Op.CUSTOMERS);
        script(Op.CUSTOMERS,new ParticipantFailure(ParticipantFailure.Kind.INVALID_RESPONSE));invoke(Op.CUSTOMERS);
        script(Op.CUSTOMERS,transientFailure());invoke(Op.CUSTOMERS); // third transient overall
        assertRefused(Op.CUSTOMERS);
    }
    @Test void aValidatedSuccessResetsThePartialOutage() {
        outage(Op.SALES,2);assertDelegated(Op.SALES);
        outage(Op.SALES,2);assertDelegated(Op.SALES); // would be open if the success had not reset the count
    }
    @Test void runtimeFailuresAreNeutralInAClosedCircuitAndPropagateSameInstance() {
        var bug=new IllegalStateException("bug");
        outage(Op.RESERVE,2);script(Op.RESERVE,bug);
        assertThat(invoke(Op.RESERVE)).isSameAs(bug);
        outage(Op.RESERVE,1);assertRefused(Op.RESERVE); // 2 + 1 transient: the runtime failure neither counted nor reset
    }

    // ---------- recovery ----------
    @Test void refusesUntilTheCooldownThenAdmitsOneProbeAndAValidatedSuccessCloses() {
        openProducts();
        clock.advance(COOLDOWN.minusMillis(1));assertRefused(Op.RESERVE);assertRefused(Op.RELEASE);
        clock.advance(Duration.ofMillis(1));
        assertDelegated(Op.RELEASE); // the probe may be any Products operation and closes the shared circuit
        assertDelegated(Op.RESERVE);assertDelegated(Op.RESERVE);assertDelegated(Op.RESERVE);
    }
    @Test void aTransientProbeFailureReopensAndWaitsAnewFromTheFailure() {
        openProducts();clock.advance(COOLDOWN);
        script(Op.RESERVE,transientFailure());invoke(Op.RESERVE); // failed probe
        script(Op.RESERVE,null);assertRefused(Op.RESERVE);
        clock.advance(COOLDOWN.minusMillis(1));assertRefused(Op.RESERVE);
        clock.advance(Duration.ofMillis(1));assertDelegated(Op.RESERVE);
    }
    @Test void aNeutralProbeFreesItWithoutClosingTheCircuit() {
        openProducts();clock.advance(COOLDOWN);
        for(var kind:List.of(ParticipantFailure.Kind.REJECTED,ParticipantFailure.Kind.AUTHORIZATION,ParticipantFailure.Kind.INVALID_RESPONSE,ParticipantFailure.Kind.LOCAL_FAILURE)) {
            script(Op.RESERVE,new ParticipantFailure(kind));
            assertThat(invoke(Op.RESERVE)).isInstanceOfSatisfying(ParticipantFailure.class,e->assertThat(e.kind()).isNotEqualTo(ParticipantFailure.Kind.CIRCUIT_OPEN));
        }
        script(Op.RESERVE,transientFailure());invoke(Op.RESERVE); // one transient is enough to reopen: it was never declared healthy
        script(Op.RESERVE,null);assertRefused(Op.RESERVE);
    }
    @Test void runtimeExceptionsAndErrorsFreeTheProbeAndPropagateUnchanged() {
        openProducts();clock.advance(COOLDOWN);
        var bug=new IllegalStateException("bug");script(Op.RESERVE,bug);
        assertThat(invoke(Op.RESERVE)).isSameAs(bug);
        var error=new AssertionError("fatal");script(Op.RELEASE,error);
        assertThat(invoke(Op.RELEASE)).isSameAs(error); // the probe was freed after the exception, so another one is admitted
        script(Op.RESERVE,null);script(Op.RELEASE,null);
        int before=delegate.calls(Op.RESERVE);
        assertThat(invoke(Op.RESERVE)).isNull();assertThat(delegate.calls(Op.RESERVE)).isEqualTo(before+1);
        script(Op.RESERVE,transientFailure());invoke(Op.RESERVE);
        script(Op.RESERVE,null);
        assertThat(invoke(Op.RESERVE)).isNull(); // the previous probe closed the circuit, so one transient does not reopen it
    }

    // ---------- through the use case ----------
    @Test void openSalesBeforeDispatchSuspendsWithAtMostOneLocalAttemptAndKeepsTheReservation() {
        outage(Op.SALES,3);int salesCallsBefore=delegate.calls(Op.SALES);
        var store=new Store(reservedSaga());var pauses=new AtomicInteger();
        usecase(store,pauses).execute(store.row.id());
        assertThat(delegate.calls(Op.SALES)).isEqualTo(salesCallsBefore);assertThat(delegate.calls(Op.RELEASE)).isZero();
        assertThat(store.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(store.row.failedStep()).isNull();
        assertThat(store.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");assertThat(store.row.reservation()).isEqualTo(reservation);
        assertThat(store.row.execution().attempts()).isEqualTo(1);assertThat(pauses).hasValue(0);
        assertPublicProjectionUnchanged(store);
    }
    @Test void openSalesAfterAPriorAmbiguousSaleKeepsTheMarkerAndConsumesAtMostOneMoreAttempt() {
        outage(Op.SALES,3);
        Saga ambiguous=reservedSaga().attempt("register-sale",Instant.EPOCH); // an earlier POST whose outcome is unknown
        var store=new Store(ambiguous);var pauses=new AtomicInteger();
        usecase(store,pauses).execute(store.row.id());
        assertThat(delegate.calls(Op.RELEASE)).isZero();
        assertThat(store.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(store.row.failedStep()).isNull();
        assertThat(store.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED");assertThat(store.row.reservation()).isEqualTo(reservation);
        assertThat(store.row.execution().pendingStep()).isEqualTo("register-sale");
        assertThat(store.row.execution().attempts()-ambiguous.execution().attempts()).isEqualTo(1);
        assertThat(store.row.execution().attempts()).isLessThan(3);assertThat(pauses).hasValue(0);
        assertPublicProjectionUnchanged(store);
    }
    @Test void openCustomersCircuitIsNeverAParticipantRejection() {
        outage(Op.CUSTOMERS,3);
        var store=new Store(freshSaga());
        usecase(store,new AtomicInteger()).execute(store.row.id());
        assertThat(store.row.status()).isEqualTo(Saga.Status.RUNNING);assertThat(store.row.failedStep()).isNull();
        assertThat(store.row.errorDetail()).isEqualTo("OUTCOME_UNRESOLVED").isNotEqualTo("PARTICIPANT_REJECTED");
        assertThat(store.row.reservation()).isNull();assertThat(delegate.calls(Op.RESERVE)).isZero();
        assertPublicProjectionUnchanged(store);
    }
    @Test void openProductsDuringCompensationEndsFailedAndNeverClaimsTheRelease() {
        openProducts();
        Saga rejectedSale=reservedSaga().reject("register-sale",Instant.EPOCH); // the sale was confirmed rejected: release is due
        var store=new Store(rejectedSale);var pauses=new AtomicInteger();
        usecase(store,pauses).execute(store.row.id());
        assertThat(delegate.calls(Op.RELEASE)).isZero();
        assertThat(store.row.status()).isEqualTo(Saga.Status.FAILED).isNotEqualTo(Saga.Status.COMPENSATED);
        assertThat(store.row.errorDetail()).isEqualTo("COMPENSATION_EXHAUSTED");
        assertThat(store.row.reservation()).isEqualTo(reservation);assertThat(pauses).hasValue(0);
        assertPublicProjectionUnchanged(store);
    }
    @Test void successfulProbeOfAnOpenCircuitLetsTheUseCaseCompleteAfterTheCooldown() {
        outage(Op.SALES,3);clock.advance(COOLDOWN);
        var store=new Store(reservedSaga());
        usecase(store,new AtomicInteger()).execute(store.row.id());
        assertThat(store.row.status()).isEqualTo(Saga.Status.COMPLETED);assertThat(store.row.saleId()).isEqualTo(sale);
    }
    RegisterSale usecase(Store store,AtomicInteger pauses){
        return new RegisterSale(store,store,guarded,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),UUID::randomUUID,n->pauses.incrementAndGet(),3);
    }
    /** The four public statuses and the private markers are unchanged, and nothing about the circuit leaks into the row. */
    void assertPublicProjectionUnchanged(Store store) {
        assertThat(Arrays.stream(Saga.Status.values()).map(Enum::name).toList()).containsExactly("RUNNING","COMPLETED","COMPENSATED","FAILED");
        for(Saga saved:store.saved) {
            assertThat(saved.errorDetail()).isIn((Object[])new String[]{null,"OUTCOME_UNRESOLVED","PARTICIPANT_REJECTED","COMPENSATION_EXHAUSTED"});
            assertThat(String.valueOf(saved)).doesNotContainIgnoringCase("circuit");
        }
    }
    static final class Store implements SagaStore,ExecutionCoordinator {
        Saga row;final List<Saga> saved=new ArrayList<>();Store(Saga row){this.row=row;}
        public Created create(Saga saga){throw new UnsupportedOperationException();}
        public Optional<Saga> find(UUID id){return Optional.of(row);}
        public List<UUID> running(int limit){return List.of(row.id());}
        public boolean healthy(){return true;}
        public Optional<Lease> tryOwn(UUID id){return Optional.of(new Lease(){public Saga load(){return row;}public void save(Saga saga){row=saga;saved.add(saga);}public void close(){}});}
    }
}
