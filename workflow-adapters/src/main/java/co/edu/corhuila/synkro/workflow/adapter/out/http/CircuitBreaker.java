package co.edu.corhuila.synkro.workflow.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Three-state breaker for one participant (CLOSED, OPEN, HALF_OPEN), thread-safe through one monitor.
 * Fail-fast beats consuming every saga's retry budget against a participant that is down.
 * State is process-local and never part of the saga projection or the stored execution record.
 */
final class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }
    /** Permission to dispatch; must be settled exactly once with success(), failure() or neutral(). */
    interface Permit { void success(); void failure(); void neutral(); }
    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private State state=State.CLOSED;
    private int failures;
    private Instant openedAt;
    private boolean probing;
    private long generation;
    CircuitBreaker(int threshold,Duration cooldown,Clock clock) {
        if(threshold<1||cooldown.isNegative()||cooldown.isZero())throw new IllegalArgumentException("Invalid breaker settings");
        this.threshold=threshold;this.cooldown=cooldown;this.clock=clock;
    }
    synchronized State state(){return refresh();}
    /** Returns a permit, or null when the call must fail fast without any network use. */
    synchronized Permit acquire() {
        State current=refresh();
        if(current==State.OPEN||(current==State.HALF_OPEN&&probing))return null;
        boolean probe=current==State.HALF_OPEN;
        if(probe)probing=true;
        return new Settled(probe);
    }
    private State refresh() {
        if(state==State.OPEN&&!clock.instant().isBefore(openedAt.plus(cooldown))){state=State.HALF_OPEN;probing=false;}
        return state;
    }
    private void open(){generation++;state=State.OPEN;openedAt=clock.instant();probing=false;}
    private final class Settled implements Permit {
        private final boolean probe;
        private final long acquiredGeneration;
        private boolean done;
        Settled(boolean probe){this.probe=probe;this.acquiredGeneration=generation;}
        public void success() {
            synchronized(CircuitBreaker.this) {
                if(!settle())return;
                failures=0;
                if(probe){generation++;state=State.CLOSED;probing=false;}
            }
        }
        public void failure() {
            synchronized(CircuitBreaker.this) {
                if(!settle())return;
                if(probe)open();
                else if(state==State.CLOSED&&++failures>=threshold)open();
            }
        }
        /** Not an outage and not a validated success (business 4xx, auth, local, contract): leaves state as is and frees a probe. */
        public void neutral() {
            synchronized(CircuitBreaker.this) {
                if(!settle())return;
                if(probe&&state==State.HALF_OPEN)probing=false;
            }
        }
        private boolean settle(){
            if(done)return false;
            done=true;
            // An earlier circuit cycle cannot settle work in the recovered cycle.
            return acquiredGeneration==generation;
        }
    }
}
