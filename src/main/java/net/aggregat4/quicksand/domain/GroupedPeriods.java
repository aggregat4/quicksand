package net.aggregat4.quicksand.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

public enum GroupedPeriods implements GroupedPeriod {

    TODAY("Today") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return todayStart(clock);
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return todayStart(clock).plusDays(1);
        }
    },
    THIS_WEEK("This Week") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return beginningOfWeek(clock);
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return todayStart(clock);
        }
    },
    LAST_WEEK("Last Week") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return beginningOfWeek(clock).minusWeeks(1);
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return beginningOfWeek(clock);
        }
    },
    THIS_MONTH("This Month") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return beginningOfMonth(clock);
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return beginningOfWeek(clock).minusWeeks(1);
        }
    },
    LAST_THREE_MONTHS("Last Three Months") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return beginningOfMonth(clock).minusMonths(2);
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return beginningOfMonth(clock);
        }
    },
    OLDER("Older") {
        @Override
        public ZonedDateTime startOfPeriod(Clock clock) {
            return LocalDate.of(1900, 1, 1).atStartOfDay(clock.getZone());
        }

        @Override
        public ZonedDateTime startOfNextPeriod(Clock clock) {
            return beginningOfMonth(clock).minusMonths(2);
        }
    };

    private final String displayName;

    GroupedPeriods(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public Optional<String> displayName() {
        return Optional.of(this.displayName);
    }

    private static ZonedDateTime todayStart(Clock clock) {
        return LocalDate.now(clock).atStartOfDay(clock.getZone());
    }

    private static ZonedDateTime beginningOfWeek(Clock clock) {
        return GroupedPeriod.getBeginningOfWeek(clock).atStartOfDay(clock.getZone());
    }

    private static ZonedDateTime beginningOfMonth(Clock clock) {
        return GroupedPeriod.getBeginningOfMonth(clock).atStartOfDay(clock.getZone());
    }
}
