package co.edu.corhuila.synkro.workflow;

import co.edu.corhuila.synkro.workflow.domain.model.*;
import co.edu.corhuila.synkro.workflow.application.port.out.*;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Action;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Result;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Step;
import co.edu.corhuila.synkro.workflow.application.usecase.RegisterSale;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Issue 286, unit level: the audit port receives a coherent, typed trace. The participants here are
 * fakes, so this proves the use case's audit contract only; the joint capture with the real HTTP
 * participants is in workflow-adapters (SafeAuditTrailIntegrationTest).
 */
class RegisterSaleAuditTest {
    static final String TRACE = "11111111-2222-4333-8444-555555555555";
    final UUID customer = UUID.randomUUID(), product = UUID.randomUUID(), caller = UUID.randomUUID();
    final SaleInput input = new SaleInput(customer, List.of(new SaleInput.Line(product, 2)));
    final Clock clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);
    final RegisterSaleTest.Memory store = new RegisterSaleTest.Memory();
    final List<AuditEvent> events = new ArrayList<>();
    final Script participants = new Script();
    final RegisterSale usecase = new RegisterSale(store, store, participants, clock, UUID::randomUUID, millis -> { }, 3, events::add);

    @Test void auditEventDeclaresOnlyTimestampIdentifiersAndConstants() {
        var types = new LinkedHashMap<String, Class<?>>();
        for (RecordComponent component : AuditEvent.class.getRecordComponents()) types.put(component.getName(), component.getType());
        assertThat(types).containsOnlyKeys("timestamp", "correlationId", "sagaId", "step", "actorId", "action", "result");
        assertThat(types.remove("correlationId")).isEqualTo(String.class);
        assertThat(types.values()).allSatisfy(type -> assertThat(type == Instant.class || type == UUID.class || type.isEnum()).isTrue());
    }

    @Test void completedSagaAuditsEveryStepWithOneTraceSagaAndActor() {
        Saga saga = usecase.start(input, caller, "key-12345", TRACE).saga();
        usecase.execute(saga.id());
        assertThat(events).extracting(AuditEvent::action, AuditEvent::step, AuditEvent::result).containsExactly(
            tuple(Action.START_SAGA, null, Result.CREATED),
            tuple(Action.EXECUTE_STEP, Step.VALIDATE_CUSTOMER, Result.SUCCEEDED),
            tuple(Action.EXECUTE_STEP, Step.RESERVE_STOCK, Result.SUCCEEDED),
            tuple(Action.EXECUTE_STEP, Step.REGISTER_SALE, Result.SUCCEEDED),
            tuple(Action.FINISH_SAGA, null, Result.COMPLETED));
        assertThat(events).allSatisfy(event -> {
            assertThat(event.sagaId()).isEqualTo(saga.id());
            assertThat(event.correlationId()).isEqualTo(TRACE);
            assertThat(event.actorId()).isEqualTo(caller);
            assertThat(event.timestamp()).isEqualTo(clock.instant());
        });
    }

    @Test void startReplayForbiddenAndConflictAreAudited() {
        usecase.start(input, caller, "key-12345", TRACE);
        usecase.start(input, caller, "key-12345", TRACE);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> usecase.start(input, stranger, "key-12345", TRACE)).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(() -> usecase.start(new SaleInput(customer, List.of(new SaleInput.Line(product, 3))), caller, "key-12345", TRACE)).isInstanceOf(WorkflowError.class);
        assertThat(events).extracting(AuditEvent::result).containsExactly(Result.CREATED, Result.REPLAYED, Result.FORBIDDEN, Result.CONFLICT);
        assertThat(events.get(2).actorId()).isEqualTo(stranger);
    }

    @Test void rejectionAndCompensationAreAuditedInOrder() {
        participants.reject = "register-sale";
        Saga saga = usecase.start(input, caller, "key-12345", TRACE).saga();
        usecase.execute(saga.id());
        usecase.execute(saga.id());
        assertThat(events).extracting(AuditEvent::action, AuditEvent::step, AuditEvent::result).contains(
            tuple(Action.EXECUTE_STEP, Step.REGISTER_SALE, Result.REJECTED),
            tuple(Action.COMPENSATE, Step.RELEASE_STOCK, Result.SUCCEEDED),
            tuple(Action.FINISH_SAGA, null, Result.COMPENSATED));
        assertThat(events).allSatisfy(event -> assertThat(event.correlationId()).isEqualTo(TRACE));
    }

    @Test void exhaustedTransientFailuresAreAuditedAsUnresolvedAndNeverFinished() {
        participants.transientSale = true;
        Saga saga = usecase.start(input, caller, "key-12345", TRACE).saga();
        usecase.execute(saga.id());
        List<Result> sale = events.stream().filter(e -> e.step() == Step.REGISTER_SALE).map(AuditEvent::result).toList();
        assertThat(sale).containsExactly(Result.TRANSIENT_FAILURE, Result.TRANSIENT_FAILURE, Result.TRANSIENT_FAILURE, Result.UNRESOLVED);
        assertThat(events).noneMatch(e -> e.action() == Action.FINISH_SAGA);
    }

    @Test void backgroundRecoveryKeepsThePersistedTraceOfTheOriginalRequest() {
        Saga saga = usecase.start(input, caller, "key-12345", TRACE).saga();
        // The first run dies while saving the stock reservation result, as a crashed worker would.
        store.failWhenStep = "reserve-stock";
        assertThatThrownBy(() -> usecase.execute(saga.id())).isInstanceOf(WorkflowError.class);
        events.clear();
        store.failWhenStep = null;
        // A later scan has no request: only the persisted saga carries the trace.
        usecase.execute(saga.id());
        assertThat(events.getFirst()).extracting(AuditEvent::action, AuditEvent::result).containsExactly(Action.RESUME_SAGA, Result.RESUMED);
        assertThat(events.getLast()).extracting(AuditEvent::action, AuditEvent::result).containsExactly(Action.FINISH_SAGA, Result.COMPLETED);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.correlationId()).isEqualTo(TRACE);
            assertThat(event.sagaId()).isEqualTo(saga.id());
            assertThat(event.actorId()).isEqualTo(caller);
        });
    }

    @Test void anOpaquePersistedTraceSurvivesBackgroundExecution() {
        String trace = "process-correlation";
        Saga saga = usecase.start(input, caller, "key-12345", trace).saga();
        usecase.execute(saga.id());
        assertThat(events).isNotEmpty().allSatisfy(event -> assertThat(event.correlationId()).isEqualTo(trace));
        assertThat(AuditEvent.traceValue(null)).isNull();
        assertThat(AuditEvent.traceValue("")).isNull();
    }

    @Test void aFailingAuditTrailNeverChangesTheSagaOutcome() {
        var broken = new RegisterSale(store, store, participants, clock, UUID::randomUUID, millis -> { }, 3, event -> { throw new IllegalStateException("log backend down"); });
        Saga saga = broken.start(input, caller, "key-12345", TRACE).saga();
        broken.execute(saga.id());
        assertThat(store.find(saga.id()).orElseThrow().status()).isEqualTo(Saga.Status.COMPLETED);
    }

    static final class Script implements SaleParticipants {
        String reject;
        boolean transientSale;
        final UUID reservation = UUID.randomUUID();
        public void validateCustomer(UUID id, String key, String trace) { check("validate-customer"); }
        public Reservation reserve(SaleInput command, String key, String trace) {
            check("reserve-stock");
            return new Reservation(reservation, List.of(new Reservation.Line(command.lines().getFirst().productId(), 2, 125)));
        }
        public UUID register(Saga saga, String key, String trace) {
            check("register-sale");
            if (transientSale) throw new ParticipantFailure(ParticipantFailure.Kind.TRANSIENT);
            return UUID.randomUUID();
        }
        public void release(Reservation r, String key, String trace) { }
        void check(String step) { if (step.equals(reject)) throw new ParticipantFailure(ParticipantFailure.Kind.REJECTED); }
    }
}
