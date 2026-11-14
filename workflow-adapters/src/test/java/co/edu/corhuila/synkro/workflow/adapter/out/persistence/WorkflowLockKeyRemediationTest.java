package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Expected values below were folded by hand (XOR of the four 32-bit words of the UUID) and are
 * deliberately written as literals, not recomputed with the production formula.
 */
class WorkflowLockKeyRemediationTest {
    static final int AUTH_REFRESH_TOKEN_NAMESPACE=0x52544F4B; // "RTOK"

    @Test void namespaceIsTheWorkflowConstantAndNeverTheAuthOne() {
        assertThat(WorkflowLockKey.NAMESPACE).isEqualTo(0x57464C4B); // "WFLK"
        assertThat(WorkflowLockKey.NAMESPACE).isNotEqualTo(AUTH_REFRESH_TOKEN_NAMESPACE);
    }
    @Test void zeroAndAllOnesFoldToZero() {
        assertThat(WorkflowLockKey.value(new UUID(0L,0L))).isZero();
        assertThat(WorkflowLockKey.value(new UUID(-1L,-1L))).isZero(); // four 0xFFFFFFFF words cancel out
    }
    @Test void signedBoundariesAreReachedWithoutOverflowOrExceptions() {
        assertThat(WorkflowLockKey.value(UUID.fromString("80000000-0000-0000-0000-000000000000"))).isEqualTo(Integer.MIN_VALUE);
        assertThat(WorkflowLockKey.value(UUID.fromString("7fffffff-0000-0000-0000-000000000000"))).isEqualTo(Integer.MAX_VALUE);
        assertThat(WorkflowLockKey.value(UUID.fromString("00000000-0000-0000-ffff-ffff00000000"))).isEqualTo(-1);
        assertThat(WorkflowLockKey.value(UUID.fromString("00000000-0000-0000-0000-000000000001"))).isEqualTo(1);
    }
    @Test void everyOneOfTheFourWordsContributes() {
        assertThat(WorkflowLockKey.value(UUID.fromString("00000001-0000-0000-0000-000000000000"))).isEqualTo(1);
        assertThat(WorkflowLockKey.value(UUID.fromString("00000000-0000-0002-0000-000000000000"))).isEqualTo(2);
        assertThat(WorkflowLockKey.value(UUID.fromString("00000000-0000-0000-0004-000000000000"))).isEqualTo(0x00040000);
        assertThat(WorkflowLockKey.value(UUID.fromString("00000000-0000-0000-0000-000000000008"))).isEqualTo(8);
    }
    @Test void arbitraryUuidsMatchPrecomputedConstants() {
        assertThat(WorkflowLockKey.value(UUID.fromString("00000001-0002-0003-0004-000000000005"))).isEqualTo(0x00060007);
        assertThat(WorkflowLockKey.value(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))).isEqualTo(0x4AE455D2);
        assertThat(WorkflowLockKey.value(UUID.fromString("deadbeef-cafe-babe-0123-456789abcdef"))).isEqualTo(0x9CDB8CD9);
    }
    @Test void valueIsStableAcrossCalls() {
        UUID id=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertThat(WorkflowLockKey.value(id)).isEqualTo(WorkflowLockKey.value(new UUID(id.getMostSignificantBits(),id.getLeastSignificantBits())));
    }
    @Test void differentUuidsMayCollideOnPurposeAndThatIsADocumentedPossibility() {
        UUID first=UUID.fromString("00000001-0000-0000-0000-000000000000");
        UUID second=UUID.fromString("00000000-0000-0001-0000-000000000000");
        assertThat(first).isNotEqualTo(second);
        assertThat(WorkflowLockKey.value(first)).isEqualTo(1).isEqualTo(WorkflowLockKey.value(second));
    }
}
