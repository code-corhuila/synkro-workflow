package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FullJitterDelayTest {
    @Test void boundIsMinOf2000AndDoublingFrom100() {
        assertThat(List.of(1,2,3,4,5,6,50,Integer.MAX_VALUE).stream().map(FullJitterDelay::bound).toList())
            .containsExactly(100L,200L,400L,800L,1600L,2000L,2000L,2000L);
    }
    @Test void everyWaitIsDrawnFromZeroUpToTheBoundWithoutTheLegacyFloor() {
        var asked=new ArrayList<Long>();var slept=new ArrayList<Long>();
        var delay=new FullJitterDelay(bound->{asked.add(bound);return 0;},slept::add);
        delay.pause(1);delay.pause(2);delay.pause(6);
        assertThat(asked).containsExactly(100L,200L,2000L);
        assertThat(slept).containsExactly(0L,0L,0L); // legacy bound+random(0..bound/2) could never wait less than the bound
        slept.clear();new FullJitterDelay(bound->bound,slept::add).pause(3);
        assertThat(slept).containsExactly(400L); // never above the bound (legacy reached 1.5x)
    }
    @Test void aDrawOutsideTheBoundIsRejectedWithoutWaiting() {
        var slept=new ArrayList<Long>();
        assertThatThrownBy(()->new FullJitterDelay(bound->bound+1,slept::add).pause(1)).isInstanceOf(WorkflowError.class);
        assertThatThrownBy(()->new FullJitterDelay(bound->-1,slept::add).pause(1)).isInstanceOf(WorkflowError.class);
        assertThat(slept).isEmpty();
    }
    @Test void systemRandomnessStaysInsideTheFullRange() {
        var slept=new ArrayList<Long>();var system=FullJitterDelay.system();
        var delay=new FullJitterDelay(bound->{var v=java.util.concurrent.ThreadLocalRandom.current().nextLong(bound+1);return v;},slept::add);
        for(int i=0;i<500;i++)delay.pause(2);
        assertThat(slept).allMatch(v->v>=0&&v<=200).anyMatch(v->v<100);
        assertThat(system).isNotNull();
    }
    @Test void interruptionPreservesTheFlagAndStopsTheRetry() {
        var delay=new FullJitterDelay(b->1,millis->{throw new InterruptedException();});
        try {
            assertThatThrownBy(()->delay.pause(1)).isInstanceOf(WorkflowError.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {Thread.interrupted();}
    }
}
