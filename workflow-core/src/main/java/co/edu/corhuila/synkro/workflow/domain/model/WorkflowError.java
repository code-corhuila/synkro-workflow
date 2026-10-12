package co.edu.corhuila.synkro.workflow.domain.model;

/** Fixed classifications only; never retain participant/driver exception text. */
public final class WorkflowError extends RuntimeException {
    public WorkflowError(String code) { super(code); }
}
