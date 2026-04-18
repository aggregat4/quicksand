package net.aggregat4.quicksand.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;

public interface GroupedPeriod {
  static LocalDate getBeginningOfWeek(Clock clock) {
    return LocalDate.now(clock)
        // now change this date to the beginning of the week (that's the "1") and do this in a
        // locale dependent way (US starts on Sunday, rest of the world on Monday)
        .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
  }

  static LocalDate getBeginningOfMonth(Clock clock) {
    return LocalDate.now(clock)
        // now change this date to the beginning of the week (that's the "1") and do this in a
        // locale dependent way (US starts on Sunday, rest of the world on Monday)
        .with(TemporalAdjusters.firstDayOfMonth());
  }

  Optional<String> displayName();

  ZonedDateTime startOfPeriod(Clock clock);

  ZonedDateTime startOfNextPeriod(Clock clock);

  default boolean matches(EmailHeader emailHeader, Clock clock) {
    ZonedDateTime start = startOfPeriod(clock);
    ZonedDateTime end = startOfNextPeriod(clock);
    return start.isBefore(end)
        && !emailHeader.receivedDateTime().isBefore(start)
        && emailHeader.receivedDateTime().isBefore(end);
  }
}
