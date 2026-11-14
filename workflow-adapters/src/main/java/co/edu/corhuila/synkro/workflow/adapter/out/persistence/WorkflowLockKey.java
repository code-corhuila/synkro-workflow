package co.edu.corhuila.synkro.workflow.adapter.out.persistence;
import java.util.UUID;
/** Compilation-only scaffold; no key policy implemented yet. */
public final class WorkflowLockKey {
    public static final int NAMESPACE=0;
    private WorkflowLockKey() {}
    public static int value(UUID id) { return 0; }
}
