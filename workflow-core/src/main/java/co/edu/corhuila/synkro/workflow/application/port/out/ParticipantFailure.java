package co.edu.corhuila.synkro.workflow.application.port.out;
public final class ParticipantFailure extends RuntimeException {
    public enum Kind { REJECTED, TRANSIENT, AUTHORIZATION, INVALID_RESPONSE, LOCAL_FAILURE }
    private final Kind kind;
    public ParticipantFailure(Kind kind) { super(kind.name());this.kind=kind; }
    public Kind kind() { return kind; }
}
