package net.aggregat4.quicksand.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static net.aggregat4.quicksand.domain.GroupedPeriod.getBeginningOfMonth;
import static net.aggregat4.quicksand.domain.GroupedPeriod.getBeginningOfWeek;

public enum GroupedPeriods implements GroupedPeriod {

    TODAY("Today",
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()),
            LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())),
    THIS_WEEK("This Week",
            getBeginningOfWeek().atStartOfDay(ZoneId.systemDefault()),
            getBeginningOfWeek()
                    // jump ahead one week since we want to check against the start of the next week
                    .plusWeeks(1)
                    .atStartOfDay(ZoneId.systemDefault())),
    LAST_WEEK("Last Week",
            getBeginningOfWeek().minusWeeks(1).atStartOfDay(ZoneId.systemDefault()),
            getBeginningOfWeek().atStartOfDay(ZoneId.systemDefault())),
    THIS_MONTH("This Month",
            getBeginningOfMonth().atStartOfDay(ZoneId.systemDefault()),
            getBeginningOfMonth().plusMonths(1).atStartOfDay(ZoneId.systemDefault())),
    LAST_THREE_MONTHS("Last Three Months",
            getBeginningOfMonth().minusMonths(2).atStartOfDay(ZoneId.systemDefault()),
            getBeginningOfMonth().plusMonths(1).atStartOfDay(ZoneId.systemDefault())),
    OLDER("Older",
            LocalDate.now().minusYears(100).atStartOfDay(ZoneId.systemDefault()),
            LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()));


    private final String displayName;
    private final ZonedDateTime startOfPeriod;
    private final ZonedDateTime startOfNextPeriod;

    GroupedPeriods(String displayName, ZonedDateTime startOfPeriod, ZonedDateTime startOfNextPeriod) {
        this.displayName = displayName;
        this.startOfPeriod = startOfPeriod;
        this.startOfNextPeriod = startOfNextPeriod;
    }

    @Override
    public Optional<String> displayName() {
        return Optional.of(this.displayName);
    }

    @Override
    public ZonedDateTime startOfPeriod() {
        return this.startOfPeriod;
    }

    @Override
    public ZonedDateTime startOfNextPeriod() {
        return this.startOfNextPeriod;
    }

    @Override
    public boolean matches(EmailHeader emailHeader) {
        return GroupedPeriod.super.matches(emailHeader);
    }
}
