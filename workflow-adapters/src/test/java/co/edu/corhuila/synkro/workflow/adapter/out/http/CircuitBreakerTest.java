package co.edu.corhuila.synkro.workflow.adapter.out.http;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Unit tests with an injected clock: no network, no sleeping. */
class CircuitBreakerTest {
    static final class TestClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-14T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(Duration d){now=now.plus(d);}
    }
    final TestClock clock=new TestClock();
    final CircuitBreaker breaker=new CircuitBreaker(3,Duration.ofSeconds(15),clock);
    void fail(int times){for(int i=0;i<times;i++)breaker.acquire().failure();}
    @Test void opensAfterThreeConsecutiveFailuresAndFailsFast() {
        fail(2);assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        fail(1);assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.acquire()).isNull();assertThat(breaker.acquire()).isNull();
    }
    @Test void aValidatedSuccessResetsTheConsecutiveCountButNeutralOutcomesDoNot() {
        fail(2);breaker.acquire().neutral();fail(0);
        breaker.acquire().failure();assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN); // neutral did not reset
        var other=new CircuitBreaker(3,Duration.ofSeconds(15),clock);
        other.acquire().failure();other.acquire().failure();other.acquire().success();other.acquire().failure();other.acquire().failure();
        assertThat(other.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void staysOpenUntilTheCooldownThenAllowsExactlyOneProbe() {
        fail(3);clock.advance(Duration.ofSeconds(14).plusMillis(999));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);assertThat(breaker.acquire()).isNull();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        var probe=breaker.acquire();assertThat(probe).isNotNull();
        assertThat(breaker.acquire()).isNull(); // single probe in flight
        probe.success();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);assertThat(breaker.acquire()).isNotNull();
    }
    @Test void aFailedProbeReopensForAFullCooldown() {
        fail(3);clock.advance(Duration.ofSeconds(15));
        breaker.acquire().failure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);assertThat(breaker.acquire()).isNull();
        clock.advance(Duration.ofSeconds(14));assertThat(breaker.acquire()).isNull();
        clock.advance(Duration.ofSeconds(1));assertThat(breaker.acquire()).isNotNull();
    }
    @Test void aNeutralProbeNeverLeavesTheCircuitStuckHalfOpenNorClosesIt() {
        fail(3);clock.advance(Duration.ofSeconds(15));
        breaker.acquire().neutral();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        var next=breaker.acquire();assertThat(next).isNotNull();next.success();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void aPermitSettlesOnlyOnce() {
        fail(3);clock.advance(Duration.ofSeconds(15));var probe=breaker.acquire();
        probe.success();probe.failure();probe.neutral();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void aStaleClosedEraFailureCannotReopenAnAlreadyRecoveredCircuit() {
        var stale=breaker.acquire();fail(3);clock.advance(Duration.ofSeconds(15));breaker.acquire().success();
        stale.failure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void staleFailuresFromBeforeOpeningCannotReopenTheRecoveredCircuit() {
        var stale = List.of(breaker.acquire(), breaker.acquire(), breaker.acquire());
        fail(3);
        clock.advance(Duration.ofSeconds(15));
        breaker.acquire().success();
        stale.forEach(CircuitBreaker.Permit::failure);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void staleSuccessCannotResetTheRecoveredCircuitsNewFailureCount() {
        var stale = breaker.acquire();
        fail(3);
        clock.advance(Duration.ofSeconds(15));
        breaker.acquire().success();
        fail(2);
        stale.success();
        fail(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
    @Test void invalidSettingsAreRejected() {
        assertThatThrownBy(()->new CircuitBreaker(0,Duration.ofSeconds(1),clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CircuitBreaker(1,Duration.ZERO,clock)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentCallersGetExactlyOneHalfOpenProbe() throws Exception {
        fail(3);clock.advance(Duration.ofSeconds(15));
        int threads=16;var granted=new AtomicInteger();var gate=new CyclicBarrier(threads);
        try(var pool=Executors.newFixedThreadPool(threads)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<threads;i++)futures.add(pool.submit(()->{try{gate.await();}catch(Exception e){throw new IllegalStateException(e);}if(breaker.acquire()!=null)granted.incrementAndGet();}));
            for(var f:futures)f.get(10,TimeUnit.SECONDS);
        }
        assertThat(granted).hasValue(1);
    }
    @Test void concurrentFailuresOpenOnceAndNeverCorruptTheState() throws Exception {
        int threads=16;var gate=new CyclicBarrier(threads);
        try(var pool=Executors.newFixedThreadPool(threads)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<threads;i++)futures.add(pool.submit(()->{try{gate.await();}catch(Exception e){throw new IllegalStateException(e);}var p=breaker.acquire();if(p!=null)p.failure();}));
            for(var f:futures)f.get(10,TimeUnit.SECONDS);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);assertThat(breaker.acquire()).isNull();
    }
}
