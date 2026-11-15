package co.edu.corhuila.synkro.workflow.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Process-local admission control; synchronization never spans the participant call. */
final class ParticipantCircuit {
    interface Token { void success(); void failure(); void neutral(); }
    private enum Outcome { SUCCESS, FAILURE, NEUTRAL }
    private final Clock clock;
    private final int threshold;
    private final Duration cooldown;
    private int failures;
    private long generation;
    private Instant blockedUntil;
    private boolean probeInFlight;

    ParticipantCircuit(Clock clock,int threshold,Duration cooldown) {
        if(threshold<=0 || cooldown.isZero() || cooldown.isNegative())
            throw new IllegalArgumentException("Positive circuit limits required");
        this.clock=clock;
        this.threshold=threshold;
        this.cooldown=cooldown;
    }
    synchronized Token tryEnter() {
        boolean probe=blockedUntil!=null;
        if(probe) {
            if(clock.instant().isBefore(blockedUntil) || probeInFlight)return null;
            probeInFlight=true;
        }
        return new Admission(generation,probe);
    }
    private void reopen() {
        generation++;
        blockedUntil=clock.instant().plus(cooldown);
        probeInFlight=false;
    }
    private final class Admission implements Token {
        private final long admittedGeneration;
        private final boolean probe;
        private boolean finished;
        Admission(long admittedGeneration,boolean probe) {
            this.admittedGeneration=admittedGeneration;
            this.probe=probe;
        }
        public void success() { settle(Outcome.SUCCESS); }
        public void failure() { settle(Outcome.FAILURE); }
        public void neutral() { settle(Outcome.NEUTRAL); }
        private void settle(Outcome outcome) {
            synchronized(ParticipantCircuit.this) {
                if(finished)return;
                finished=true;
                if(admittedGeneration!=generation)return;
                switch(outcome) {
                    case SUCCESS -> {
                        failures=0;
                        if(probe) {
                            generation++;
                            blockedUntil=null;
                            probeInFlight=false;
                        }
                    }
                    case FAILURE -> {
                        if(probe || ++failures>=threshold)reopen();
                    }
                    case NEUTRAL -> { if(probe)probeInFlight=false; }
                }
            }
        }
    }
}
