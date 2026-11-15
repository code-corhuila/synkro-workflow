package co.edu.corhuila.synkro.workflow.adapter.out.logging;

import co.edu.corhuila.synkro.workflow.application.port.out.AuditEvent;
import co.edu.corhuila.synkro.workflow.application.port.out.AuditTrail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;

/**
 * Writes each audit event as one JSON object through SLF4J. The object is assembled only from
 * the typed event and the opaque correlation value. JSON encoding escapes that value so it cannot
 * inject another field or log line. Failures of the logging backend never alter a saga.
 */
public final class Slf4jAuditTrail implements AuditTrail {
    public static final String SERVICE = "synkro-workflow";
    public static final String LOGGER_NAME = "synkro.workflow.audit";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Logger log;

    public Slf4jAuditTrail() { this(LoggerFactory.getLogger(LOGGER_NAME)); }

    Slf4jAuditTrail(Logger log) { this.log = log; }

    @Override
    public void record(AuditEvent event) {
        try {
            log.info("{}", json(event));
        } catch (RuntimeException ignored) {
            // The saga outcome does not depend on its audit line.
        }
    }

    static String json(AuditEvent e) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("timestamp", e.timestamp().toString());
        fields.put("service", SERVICE);
        if (e.correlationId() != null) fields.put("correlationId", e.correlationId());
        fields.put("sagaId", e.sagaId().toString());
        if (e.step() != null) fields.put("step", e.step().wire());
        if (e.actorId() != null) fields.put("actorId", e.actorId().toString());
        fields.put("action", e.action().wire());
        fields.put("result", e.result().wire());
        try {
            return JSON.writeValueAsString(fields);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode audit event");
        }
    }
}
