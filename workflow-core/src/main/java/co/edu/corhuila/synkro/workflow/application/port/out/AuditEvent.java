package co.edu.corhuila.synkro.workflow.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The closed set of facts an audit record may state. Business identifiers are UUIDs; actions and outcomes use a
 * fixed vocabulary. The correlation value is the opaque request trace required by the HTTP contract.
 * No request body, credential or exception is accepted as an audit field.
 *
 * @param correlationId the opaque request trace
 * @param actorId       the validated human initiator, when known
 */
public record AuditEvent(Instant timestamp, String correlationId, UUID sagaId, Step step, UUID actorId, Action action, Result result) {
    public enum Step {
        VALIDATE_CUSTOMER("validate-customer"), RESERVE_STOCK("reserve-stock"), REGISTER_SALE("register-sale"), RELEASE_STOCK("release-stock");
        private final String wire;
        Step(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static Optional<Step> of(String wire) {
            for (Step step : values()) if (step.wire.equals(wire)) return Optional.of(step);
            return Optional.empty();
        }
    }
    public enum Action {
        START_SAGA("start-saga"), RESUME_SAGA("resume-saga"), EXECUTE_STEP("execute-step"), COMPENSATE("compensate"), FINISH_SAGA("finish-saga");
        private final String wire;
        Action(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
    public enum Result {
        CREATED("created"), REPLAYED("replayed"), FORBIDDEN("forbidden"), CONFLICT("conflict"), RESUMED("resumed"),
        SUCCEEDED("succeeded"), REJECTED("rejected"), TRANSIENT_FAILURE("transient-failure"), UNRESOLVED("unresolved"),
        COMPLETED("completed"), COMPENSATED("compensated"), FAILED("failed");
        private final String wire;
        Result(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public AuditEvent {
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(sagaId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(result);
    }

    /** Keep the original trace for asynchronous recovery as well as direct HTTP requests. */
    public static String traceValue(String trace) {
        return trace == null || trace.isEmpty() ? null : trace;
    }
}
