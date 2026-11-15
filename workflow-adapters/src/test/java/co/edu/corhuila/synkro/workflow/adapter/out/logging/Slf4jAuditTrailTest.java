package co.edu.corhuila.synkro.workflow.adapter.out.logging;

import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Action;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Result;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent.Step;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Issue 286: the SLF4J adapter writes the typed event as one allowlisted JSON object. */
class Slf4jAuditTrailTest {
    final UUID trace = UUID.randomUUID(), saga = UUID.randomUUID(), actor = UUID.randomUUID();
    final Instant at = Instant.parse("2026-10-12T12:00:00Z");

    @Test void writesExactlyTheAllowlistedFields() throws Exception {
        var event = new AuditEvent(at, trace.toString(), saga, Step.RESERVE_STOCK, actor, Action.EXECUTE_STEP, Result.SUCCEEDED);
        JsonNode json = JsonMapper.builder().build().readTree(Slf4jAuditTrail.json(event));
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("timestamp", "service", "correlationId", "sagaId", "step", "actorId", "action", "result");
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-10-12T12:00:00Z");
        assertThat(json.get("service").asText()).isEqualTo("synkro-workflow");
        assertThat(json.get("correlationId").asText()).isEqualTo(trace.toString());
        assertThat(json.get("sagaId").asText()).isEqualTo(saga.toString());
        assertThat(json.get("step").asText()).isEqualTo("reserve-stock");
        assertThat(json.get("actorId").asText()).isEqualTo(actor.toString());
        assertThat(json.get("action").asText()).isEqualTo("execute-step");
        assertThat(json.get("result").asText()).isEqualTo("succeeded");
    }

    @Test void omitsFieldsThatAreNotKnown() throws Exception {
        var event = new AuditEvent(at, null, saga, null, null, Action.FINISH_SAGA, Result.COMPLETED);
        JsonNode json = JsonMapper.builder().build().readTree(Slf4jAuditTrail.json(event));
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("timestamp", "service", "sagaId", "action", "result");
    }

    @Test void everyVocabularyValueIsPlainAndNeedsNoEscaping() {
        for (Step value : Step.values()) assertThat(value.wire()).matches("[a-z-]+");
        for (Action value : Action.values()) assertThat(value.wire()).matches("[a-z-]+");
        for (Result value : Result.values()) assertThat(value.wire()).matches("[a-z-]+");
    }

    @Test void writesOneLineToTheAuditLogger() {
        try (var logs = new LogCapture()) {
            new Slf4jAuditTrail().record(new AuditEvent(at, trace.toString(), saga, null, actor, Action.START_SAGA, Result.CREATED));
            assertThat(logs.auditLines()).hasSize(1).first().asString().startsWith("{\"timestamp\"").doesNotContain("\n");
        }
    }

    @Test void opaqueCorrelationIsEscapedAsOneJsonValue() throws Exception {
        String opaque = "request-\"quoted\"\\value\nnext";
        String line = Slf4jAuditTrail.json(new AuditEvent(at, opaque, saga, Step.REGISTER_SALE, actor, Action.EXECUTE_STEP, Result.SUCCEEDED));
        assertThat(JsonMapper.builder().build().readTree(line).get("correlationId").asText()).isEqualTo(opaque);
        assertThat(line).doesNotContain("\n");
    }

    @Test void aBrokenLoggerNeverThrowsIntoTheUseCase() {
        var failing = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { org.slf4j.Logger.class },
            (proxy, method, args) -> { throw new IllegalStateException("backend down"); });
        var broken = new Slf4jAuditTrail(failing);
        assertThatCode(() -> broken.record(new AuditEvent(at, trace.toString(), saga, null, actor, Action.START_SAGA, Result.CREATED))).doesNotThrowAnyException();
    }
}
