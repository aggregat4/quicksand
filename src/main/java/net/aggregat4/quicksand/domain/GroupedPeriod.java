package net.aggregat4.quicksand.domain;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;

public interface GroupedPeriod {
    static LocalDate getBeginningOfWeek() {
        return LocalDate.now()
                // now change this date to the beginning of the week (that's the "1") and do this in a locale dependent way (US starts on Sunday, rest of the world on Monday)
                .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
    }

    static LocalDate getBeginningOfMonth() {
        return LocalDate.now()
                // now change this date to the beginning of the week (that's the "1") and do this in a locale dependent way (US starts on Sunday, rest of the world on Monday)
                .with(TemporalAdjusters.firstDayOfMonth());
    }

    Optional<String> displayName();

    ZonedDateTime startOfPeriod();

    ZonedDateTime startOfNextPeriod();

    default boolean matches(EmailHeader emailHeader) {
        return !emailHeader.receivedDateTime().isBefore(startOfPeriod()) &&
                emailHeader.receivedDateTime().isBefore(startOfNextPeriod());
    }
}
