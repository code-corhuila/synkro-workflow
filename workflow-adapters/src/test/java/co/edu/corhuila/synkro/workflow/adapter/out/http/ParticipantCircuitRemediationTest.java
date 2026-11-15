package co.edu.corhuila.synkro.workflow.adapter.out.http;

import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Pure in-memory state machine driven by a manual clock; no I/O and no real time. */
class ParticipantCircuitRemediationTest {
    static final Duration COOLDOWN=Duration.ofSeconds(15);
    final ManualClock clock=new ManualClock();
    final ParticipantCircuit circuit=new ParticipantCircuit(clock,3,COOLDOWN);

    static final class ManualClock extends Clock {
        volatile Instant now=Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(Duration d){now=now.plus(d);}
    }
    ParticipantCircuit.Token enter(){var t=circuit.tryEnter();assertThat(t).as("circuit should admit a call").isNotNull();return t;}
    void denied(){assertThat(circuit.tryEnter()).as("circuit should refuse a call").isNull();}
    void fail(int times){for(int i=0;i<times;i++)enter().failure();}
    void open(){fail(3);denied();}
    /** Opens, waits the cooldown and returns the single half-open probe. */
    ParticipantCircuit.Token probe(){open();clock.advance(COOLDOWN);return enter();}

    @Test void invalidConfigurationIsRejected() {
        assertThatThrownBy(()->new ParticipantCircuit(clock,0,COOLDOWN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ParticipantCircuit(clock,-1,COOLDOWN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ParticipantCircuit(clock,3,Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ParticipantCircuit(clock,3,Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void staysClosedBelowTheThresholdAndAllowsConcurrentCalls() {
        var first=enter();var second=enter();var third=enter(); // closed circuit admits several in-flight calls
        first.failure();second.failure();
        assertThat(circuit.tryEnter()).isNotNull();
        third.neutral();
    }
    @Test void opensAfterExactlyThresholdConsecutiveFailures() {
        fail(2);assertThat(circuit.tryEnter()).isNotNull();
        enter().failure();
        denied();
    }
    @Test void aSuccessResetsTheConsecutiveCount() {
        fail(2);enter().success();fail(2);
        assertThat(circuit.tryEnter()).isNotNull(); // 2 consecutive again, not 4
        enter().failure();
        denied();
    }
    @Test void neutralOutcomesNeitherCountNorReset() {
        fail(2);enter().neutral();enter().neutral();
        assertThat(circuit.tryEnter()).isNotNull();
        enter().failure();
        denied(); // the neutral calls in between kept the partial count at 2
    }
    @Test void staysOpenUntilTheExactCooldownBoundary() {
        open();
        clock.advance(COOLDOWN.minusNanos(1));denied();
        clock.advance(Duration.ofNanos(1));
        assertThat(circuit.tryEnter()).isNotNull(); // at exactly the cooldown a probe is admitted
    }
    @Test void onlyOneSimultaneousProbeIsAdmitted() {
        var probe=probe();
        denied();denied();
        probe.neutral();
        assertThat(circuit.tryEnter()).isNotNull();
    }
    @Test void aFailedProbeReopensAndTheCooldownRestartsFromTheFailure() {
        var probe=probe();
        clock.advance(Duration.ofSeconds(5));probe.failure();
        denied();
        clock.advance(COOLDOWN.minusNanos(1));denied(); // measured from the probe failure, not from the first opening
        clock.advance(Duration.ofNanos(1));
        assertThat(circuit.tryEnter()).isNotNull();
    }
    @Test void aSuccessfulProbeClosesTheCircuitWithAFreshCount() {
        probe().success();
        var a=enter();var b=enter();var c=enter(); // fully closed again: concurrent admissions
        a.failure();b.failure();
        assertThat(circuit.tryEnter()).isNotNull(); // two failures are below the threshold
        c.failure();
        denied();
    }
    @Test void aNeutralProbeFreesItAndDoesNotClaimTheParticipantIsAvailable() {
        var probe=probe();probe.neutral();
        var next=enter(); // still half-open: a new probe is available without waiting another cooldown
        denied();         // ... and only one at a time
        next.failure();   // a single failure reopens; a closed circuit would still tolerate it
        denied();
    }
    @Test void settlingTwiceCountsOnlyOnce() {
        var token=enter();
        token.failure();token.failure();token.failure();
        assertThat(circuit.tryEnter()).isNotNull();
        token.success(); // late contradictory settlement is ignored too
        fail(1);
        assertThat(circuit.tryEnter()).isNotNull(); // 2 failures only
        enter().failure();
        denied();
    }
    @Test void aSettledProbeCannotBeSettledAgain() {
        var probe=probe();probe.failure();
        probe.success();probe.neutral();probe.failure();
        denied(); // still open from the first settlement
        clock.advance(COOLDOWN);
        var next=enter();
        next.success();
        assertThat(circuit.tryEnter()).isNotNull();
    }
    @Test void aTokenFromBeforeOpeningCannotMutateTheRecoveredGeneration() {
        var oldFailure=enter();var oldSuccess=enter();
        fail(3);denied();
        clock.advance(COOLDOWN);enter().success(); // recovered
        oldFailure.failure();
        fail(2);
        assertThat(circuit.tryEnter()).isNotNull(); // the stale failure was not counted: 2, not 3
        oldSuccess.success();                     // nor does a stale success reset the two real failures
        enter().failure();
        denied();
    }
    @Test void staleTokensCannotCloseOrFreeAnOpenOrHalfOpenCircuit() {
        var settledWhileOpenA=enter();var settledWhileOpenB=enter();var settledInProbeA=enter();var settledInProbeB=enter();
        open();
        settledWhileOpenA.success();settledWhileOpenB.neutral();
        denied(); // a stale success does not close an open circuit
        clock.advance(COOLDOWN);
        var probe=enter();
        settledInProbeA.success();settledInProbeB.neutral();
        denied(); // the probe is still outstanding; stale settlements neither close nor free it
        probe.success();
        assertThat(circuit.tryEnter()).isNotNull();
    }
    @Test void aStaleFailureDuringHalfOpenDoesNotReopenBeforeTheProbeSettles() {
        var stale=enter();
        var probe=probe();
        stale.failure();
        denied();
        probe.success();
        assertThat(circuit.tryEnter()).isNotNull(); // closed by the real probe
        fail(2);
        assertThat(circuit.tryEnter()).isNotNull(); // fresh count: stale failure was ignored
    }
    @Test void aStaleFailureWhileOpenDoesNotRestartTheCooldown() {
        var stale=enter();
        open();
        clock.advance(COOLDOWN.minusSeconds(1));
        stale.failure();
        clock.advance(Duration.ofSeconds(1));
        assertThat(circuit.tryEnter()).isNotNull();
    }
    @Test void exactlyOneOfManyConcurrentCallersWinsTheProbe() throws Exception {
        open();clock.advance(COOLDOWN);int callers=16;
        try(var pool=Executors.newFixedThreadPool(callers)) {
            var gate=new CyclicBarrier(callers);var futures=new ArrayList<Future<ParticipantCircuit.Token>>();
            for(int i=0;i<callers;i++)futures.add(pool.submit(()->{gate.await();return circuit.tryEnter();}));
            int admitted=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS)!=null)admitted++;
            assertThat(admitted).isEqualTo(1);
        }
    }
}
