package net.aggregat4.quicksand.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface EmailGroup {

    String name();

    List<EmailHeader> headers();

    abstract class AbstractEmailgroup implements EmailGroup {
        private final List<EmailHeader> headers = new ArrayList<>();

        private final ZonedDateTime startOfPeriod;
        private final ZonedDateTime startOfNextPeriod;

        public AbstractEmailgroup(List<EmailHeader> emailHeaders, ZonedDateTime startOfPeriod, ZonedDateTime startOfNextPeriod) {
            this.headers.addAll(emailHeaders);
            this.startOfPeriod = startOfPeriod;
            this.startOfNextPeriod = startOfNextPeriod;
        }

        public static LocalDate getBeginningOfWeek() {
            return LocalDate.now()
                    // now change this date to the beginning of the week (that's the "1") and do this in a locale dependent way (US starts on Sunday, rest of the world on Monday)
                    .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
        }

        public static LocalDate getBeginningOfMonth() {
            return LocalDate.now()
                    // now change this date to the beginning of the week (that's the "1") and do this in a locale dependent way (US starts on Sunday, rest of the world on Monday)
                    .with(TemporalAdjusters.firstDayOfMonth());
        }

        public void add(EmailHeader emailHeader) {
            this.headers.add(emailHeader);
        }

        @Override
        public List<EmailHeader> headers() {
            return this.headers;
        }

        public boolean matches(EmailHeader emailHeader) {
            return !emailHeader.receivedDate().isBefore(startOfPeriod) &&
                    emailHeader.receivedDate().isBefore(startOfNextPeriod);
        }
    }

    class TodayEmailGroup extends AbstractEmailgroup {
        public TodayEmailGroup(List<EmailHeader> headers) {
            super(
                    headers,
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()),
                    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()));
        }

        @Override
        public String name() {
            return "Today";
        }
    }

    class ThisWeekEmailGroup extends AbstractEmailgroup {
        public ThisWeekEmailGroup(List<EmailHeader> headers) {
            super(
                    headers,
                    getBeginningOfWeek().atStartOfDay(ZoneId.systemDefault()),
                    getBeginningOfWeek()
                            // jump ahead one week since we want to check against the start of the next week
                            .plusWeeks(1)
                            .atStartOfDay(ZoneId.systemDefault())
            );
        }

        @Override
        public String name() {
            return "This Week";
        }
    }

    class LastWeekEmailGroup extends AbstractEmailgroup {
        public LastWeekEmailGroup(List<EmailHeader> headers) {
            super(
                    headers,
                    getBeginningOfWeek().minusWeeks(1).atStartOfDay(ZoneId.systemDefault()),
                    getBeginningOfWeek().atStartOfDay(ZoneId.systemDefault()));
        }

        @Override
        public String name() {
            return "Last Week";
        }
    }

    class ThisMonthEmailGroup extends AbstractEmailgroup {
        public ThisMonthEmailGroup(List<EmailHeader> headers) {
            super(
                    headers,
                    getBeginningOfMonth().atStartOfDay(ZoneId.systemDefault()),
                    getBeginningOfMonth().plusMonths(1).atStartOfDay(ZoneId.systemDefault()));
        }

        @Override
        public String name() {
            return "This Month";
        }
    }

}
