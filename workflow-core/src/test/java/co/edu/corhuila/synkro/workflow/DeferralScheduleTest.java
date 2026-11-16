package co.edu.corhuila.synkro.workflow;

import co.edu.corhuila.synkro.workflow.application.usecase.DeferralSchedule;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeferralScheduleTest {
    @Test void standardWaitsDoubleFromThirtySecondsUpToFifteenMinutesForTenRounds() {
        var schedule = DeferralSchedule.standard();
        var waits = new ArrayList<Duration>();
        for (int round = 0; schedule.waitAfter(round).isPresent(); round++) waits.add(schedule.waitAfter(round).orElseThrow());

        assertThat(waits).containsExactly(
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8),
            Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofMinutes(15));
        assertThat(schedule.waitAfter(10)).isEmpty();
    }
    @Test void largeRoundNumbersStayAtTheCeilingWithoutOverflow() {
        var schedule = new DeferralSchedule(Duration.ofSeconds(30), Duration.ofMinutes(15), 1000);
        assertThat(schedule.waitAfter(999)).contains(Duration.ofMinutes(15));
    }
    @Test void invalidSchedulesAreRejected() {
        assertThatThrownBy(() -> new DeferralSchedule(Duration.ZERO, Duration.ofMinutes(1), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeferralSchedule(Duration.ofMinutes(2), Duration.ofMinutes(1), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeferralSchedule(Duration.ofSeconds(30), Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeferralSchedule(null, Duration.ofMinutes(1), 3)).isInstanceOf(NullPointerException.class);
    }
}
