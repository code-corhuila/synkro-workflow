package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import java.util.UUID;

/** Workflow's session-lock namespace; hash collisions serialize, never identify a saga. */
public final class WorkflowLockKey {
    public static final int NAMESPACE=0x57464C4B;
    private WorkflowLockKey() {}

    public static int value(UUID id) {
        long high=id.getMostSignificantBits(),low=id.getLeastSignificantBits();
        return (int)(high>>>32) ^ (int)high ^ (int)(low>>>32) ^ (int)low;
    }
}
