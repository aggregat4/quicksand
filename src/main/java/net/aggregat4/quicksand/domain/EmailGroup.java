package net.aggregat4.quicksand.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface EmailGroup {
    String getName();

    boolean matches(EmailHeader emailHeader);

    void add(EmailHeader emailHeader);

    List<EmailHeader> getEmailHeaders();

    abstract class AbstractEmailgroup implements EmailGroup {
        private final List<EmailHeader> headers = new ArrayList<>();

        private final ZonedDateTime startOfPeriod;
        private final ZonedDateTime startOfNextPeriod;

        public AbstractEmailgroup(ZonedDateTime startOfPeriod, ZonedDateTime startOfNextPeriod) {
            this.startOfPeriod = startOfPeriod;
            this.startOfNextPeriod = startOfNextPeriod;
        }

        @Override
        public void add(EmailHeader emailHeader) {
            this.headers.add(emailHeader);
        }

        @Override
        public List<EmailHeader> getEmailHeaders() {
            return this.headers;
        }

        @Override
        public boolean matches(EmailHeader emailHeader) {
            return !emailHeader.receivedDate().isBefore(startOfPeriod) &&
                    emailHeader.receivedDate().isBefore(startOfNextPeriod);
        }
    }

    class TodayEmailGroup extends AbstractEmailgroup {
        public TodayEmailGroup() {
            super(
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()),
                    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()));
        }

        @Override
        public String getName() {
            return "Today";
        }
    }

    class ThisWeekEmailGroup extends AbstractEmailgroup {
        public ThisWeekEmailGroup() {
            super(
                    getBeginningOfWeek().atStartOfDay(ZoneId.systemDefault()),
                    getBeginningOfWeek()
                            // jump ahead one week since we want to check against the start of the next week
                            .plusWeeks(1)
                            .atStartOfDay(ZoneId.systemDefault())
            );
        }

        private static LocalDate getBeginningOfWeek() {
            return LocalDate.now()
                    // now change this date to the beginning of the week (that's the "1") and do this in a locale dependent way (US starts on Sunday, rest of the world on Monday)
                    .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
        }

        @Override
        public String getName() {
            return "Today";
        }
    }

    // TODO: continue with groups here
    class NextWeekEmailGroup extends AbstractEmailgroup {
        public NextWeekEmailGroup() {
            super(
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()),
                    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()));
        }

        @Override
        public String getName() {
            return "Today";
        }
    }


}
