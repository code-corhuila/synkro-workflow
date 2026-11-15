package co.edu.corhuila.synkro.workflow.application.port.out;

/** Outbound port for the saga audit trail. Implementations must not throw into the use case. */
@FunctionalInterface
public interface AuditTrail {
    AuditTrail NONE = event -> { };

    void record(AuditEvent event);
}
