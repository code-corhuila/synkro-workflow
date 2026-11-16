package co.edu.corhuila.synkro.workflow.application.usecase;

import java.time.Duration;
import java.util.*;

/**
 * Strategy for the wait between rounds of a step whose participant stayed unavailable:
 * doubling from the first wait up to a ceiling, for a bounded number of rounds.
 */
public final class DeferralSchedule {
    private static final int MAX_DOUBLINGS=30;
    private final Duration first;
    private final Duration ceiling;
    private final int maxDeferrals;

    public DeferralSchedule(Duration first,Duration ceiling,int maxDeferrals) {
        Objects.requireNonNull(first);Objects.requireNonNull(ceiling);
        if(first.isZero() || first.isNegative() || ceiling.compareTo(first)<0 || maxDeferrals<1)
            throw new IllegalArgumentException("Invalid deferral schedule");
        this.first=first;this.ceiling=ceiling;this.maxDeferrals=maxDeferrals;
    }
    public static DeferralSchedule standard() {
        return new DeferralSchedule(Duration.ofSeconds(30),Duration.ofMinutes(15),10);
    }
    /** The wait before the next round, or empty when the step must go to operator review. */
    public Optional<Duration> waitAfter(int deferralsSoFar) {
        if(deferralsSoFar>=maxDeferrals) return Optional.empty();
        Duration doubled=first.multipliedBy(1L<<Math.min(Math.max(deferralsSoFar,0),MAX_DOUBLINGS));
        return Optional.of(doubled.compareTo(ceiling)>0?ceiling:doubled);
    }
}
