package co.edu.corhuila.synkro.workflow.adapter.out.http;

import co.edu.corhuila.synkro.workflow.application.port.out.RetryDelay;
import co.edu.corhuila.synkro.workflow.domain.model.WorkflowError;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.LongUnaryOperator;
import static org.assertj.core.api.Assertions.*;

/** Deterministic: the random draw and the sleep are injected, nothing here ever sleeps. */
class FullJitterRetryDelayRemediationTest {
    final List<Long> draws=new ArrayList<>(),sleeps=new ArrayList<>();

    FullJitterRetryDelay delay(LongUnaryOperator choose) {
        return new FullJitterRetryDelay(bound->{draws.add(bound);return choose.applyAsLong(bound);},sleeps::add);
    }
    @AfterEach void clearInterruptFlag(){Thread.interrupted();}

    @Test void boundsDoubleFromOneHundredAndSaturateAtTwoThousandMilliseconds() {
        assertThat(List.of(1,2,3,4,5,6,7,8,20).stream().map(FullJitterRetryDelay::bound).toList())
            .containsExactly(100L,200L,400L,800L,1600L,2000L,2000L,2000L,2000L);
    }
    @Test void nonPositiveAttemptsAreNormalizedToTheFirst() {
        for(int attempt:new int[]{0,-1,-7,Integer.MIN_VALUE})assertThat(FullJitterRetryDelay.bound(attempt)).as("attempt "+attempt).isEqualTo(100L);
    }
    @Test void hugeAttemptsSaturateWithoutOverflow() {
        for(int attempt:new int[]{31,32,33,62,63,64,65,127,128,1_000_000,Integer.MAX_VALUE})
            assertThat(FullJitterRetryDelay.bound(attempt)).as("attempt "+attempt).isEqualTo(2000L);
    }
    @Test void supportedBudgetOfThreeAttemptsDrawsInclusiveBoundsOneHundredAndTwoHundred() {
        var d=delay(bound->bound);
        d.pause(1);d.pause(2);d.pause(3);
        assertThat(draws).containsExactly(100L,200L,400L);
        assertThat(sleeps).containsExactly(100L,200L,400L); // upper bound is reachable (inclusive)
    }
    @Test void zeroAndInteriorDrawsAreSleptExactly() {
        delay(bound->0).pause(4);
        delay(bound->bound/2).pause(4);
        delay(bound->1).pause(4);
        assertThat(draws).containsExactly(800L,800L,800L);
        assertThat(sleeps).containsExactly(0L,400L,1L);
    }
    @Test void explicitBoundsAreWhatTheDrawReceivesForEveryStep() {
        var d=delay(bound->bound);
        for(int attempt=1;attempt<=7;attempt++)d.pause(attempt);
        assertThat(draws).containsExactly(100L,200L,400L,800L,1600L,2000L,2000L);
        assertThat(sleeps).isEqualTo(draws);
    }
    @Test void pauseNormalizesAndSaturatesBeforeDrawing() {
        var d=delay(bound->0);
        d.pause(0);d.pause(-3);d.pause(Integer.MAX_VALUE);
        assertThat(draws).containsExactly(100L,100L,2000L);
    }
    @Test void aDrawOutsideTheInclusiveRangeIsRejectedWithoutSleeping() {
        for(long invalid:new long[]{-1L,Long.MIN_VALUE,101L,Long.MAX_VALUE}) {
            var d=delay(bound->invalid);
            assertThatThrownBy(()->d.pause(1)).isInstanceOf(WorkflowError.class).hasMessage("INTERNAL_ERROR");
        }
        assertThat(sleeps).isEmpty();
        var late=delay(bound->bound+1);
        assertThatThrownBy(()->late.pause(6)).isInstanceOf(WorkflowError.class).hasMessage("INTERNAL_ERROR");
        assertThat(sleeps).isEmpty();
    }
    @Test void interruptionKeepsTheFlagAndFailsWithAFixedClassification() {
        var d=new FullJitterRetryDelay(bound->bound,millis->{throw new InterruptedException("secret-sentinel");});
        assertThatThrownBy(()->d.pause(2)).isInstanceOf(WorkflowError.class).hasMessage("INTERNAL_ERROR");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    @Test void normalCompletionDoesNotRaiseTheInterruptFlag() {
        delay(bound->bound).pause(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
    @Test void productionFactoryIsAPlainRetryDelayStrategy() {
        RetryDelay system=FullJitterRetryDelay.system();
        assertThat(system).isNotNull().isInstanceOf(FullJitterRetryDelay.class);
    }
}
