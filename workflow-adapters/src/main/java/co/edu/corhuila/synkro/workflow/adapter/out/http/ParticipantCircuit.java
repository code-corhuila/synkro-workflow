package co.edu.corhuila.synkro.workflow.adapter.out.http;
import java.time.Clock;
import java.time.Duration;
/** Compilation-only scaffold; every call is admitted, no breaker implemented yet. */
final class ParticipantCircuit {
    interface Token { void success(); void failure(); void neutral(); }
    ParticipantCircuit(Clock clock,int threshold,Duration cooldown) {}
    Token tryEnter() { return new Token() {
        public void success() {} public void failure() {} public void neutral() {}
    }; }
}
