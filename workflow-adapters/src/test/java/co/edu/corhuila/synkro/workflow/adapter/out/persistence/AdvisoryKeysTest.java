package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AdvisoryKeysTest {
    static final int AUTH_REFRESH_FIRST_KEY=0x52544F4B;
    static UUID of(int w0,int w1,int w2,int w3) {
        return new UUID(((long)w0<<32)|(w1&0xFFFFFFFFL),((long)w2<<32)|(w3&0xFFFFFFFFL));
    }
    @Test void firstKeyIsTheFixedWorkflowNamespaceAndDiffersFromAuthRefresh() {
        assertThat(AdvisoryKeys.FIRST).isEqualTo(0x57464C4B).isEqualTo(1464224843).isNotEqualTo(AUTH_REFRESH_FIRST_KEY);
    }
    @Test void secondKeyIsTheXorOfTheFourSigned32BitWords() {
        assertThat(AdvisoryKeys.second(new UUID(0,0))).isZero();
        assertThat(AdvisoryKeys.second(of(1,2,4,8))).isEqualTo(15);
        assertThat(AdvisoryKeys.second(of(0x12345678,0x9ABCDEF0,0x0F0F0F0F,0xF0F0F0F0)))
            .isEqualTo(0x12345678^0x9ABCDEF0^0x0F0F0F0F^0xF0F0F0F0);
        assertThat(AdvisoryKeys.second(new UUID(-1L,-1L))).isZero();
    }
    @Test void boundaryWordsKeepSignedInt32Behavior() {
        assertThat(AdvisoryKeys.second(of(Integer.MIN_VALUE,0,0,0))).isEqualTo(Integer.MIN_VALUE);
        assertThat(AdvisoryKeys.second(of(Integer.MAX_VALUE,0,0,0))).isEqualTo(Integer.MAX_VALUE);
        assertThat(AdvisoryKeys.second(of(Integer.MIN_VALUE,Integer.MAX_VALUE,0,0))).isEqualTo(-1);
        assertThat(AdvisoryKeys.second(new UUID(Long.MIN_VALUE,0))).isEqualTo(Integer.MIN_VALUE);
        assertThat(AdvisoryKeys.second(new UUID(0,Long.MAX_VALUE))).isEqualTo(Integer.MIN_VALUE);
    }
    @Test void everyWordContributesIncludingTheLowOrderWordsOfEachLong() {
        UUID base=of(0,0,0,0);
        assertThat(AdvisoryKeys.second(of(1,0,0,0))).isNotEqualTo(AdvisoryKeys.second(base));
        assertThat(AdvisoryKeys.second(of(0,1,0,0))).isEqualTo(1);
        assertThat(AdvisoryKeys.second(of(0,0,1,0))).isEqualTo(1);
        assertThat(AdvisoryKeys.second(of(0,0,0,1))).isEqualTo(1);
    }
    @Test void sameNamespaceCollisionsShareLockKeysWithoutAliasingIdentities() {
        UUID a=of(5,6,7,8),b=of(5^0x1234,6^0x1234,7,8);
        assertThat(a).isNotEqualTo(b);
        assertThat(AdvisoryKeys.second(a)).isEqualTo(AdvisoryKeys.second(b));
        assertThat(a.toString()).isNotEqualTo(b.toString());
    }
}
