package net.aggregat4.quicksand.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EmailGroupTest {
    private static final Locale TEST_LOCALE = Locale.forLanguageTag("en-DE");
    private static Locale originalLocale;
    private static TimeZone originalTimeZone;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("Europe/Berlin"));

    @BeforeAll
    static void setDefaults() {
        originalLocale = Locale.getDefault();
        originalTimeZone = TimeZone.getDefault();
        Locale.setDefault(TEST_LOCALE);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @AfterAll
    static void restoreDefaults() {
        Locale.setDefault(originalLocale);
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void groupsDescendingHeadersAcrossAllTemporalBuckets() {
        List<EmailHeader> headers = boundaryHeadersDescending();

        List<EmailGroup> groups = EmailGroup.createEmailGroups(headers, FIXED_CLOCK, SortOrder.DESCENDING);

        assertEquals(List.of("Today", "This Week", "Last Week", "This Month", "Last Three Months", "Older"),
                groups.stream().map(group -> group.name().orElseThrow()).toList());
        assertEquals(List.of(2, 2, 2, 2, 2, 1),
                groups.stream().map(EmailGroup::getNofMessages).toList());
    }

    @Test
    void groupsAscendingHeadersAcrossAllTemporalBuckets() {
        List<EmailHeader> headers = boundaryHeadersDescending().reversed();

        List<EmailGroup> groups = EmailGroup.createEmailGroups(headers, FIXED_CLOCK, SortOrder.ASCENDING);

        assertEquals(List.of("Older", "Last Three Months", "This Month", "Last Week", "This Week", "Today"),
                groups.stream().map(group -> group.name().orElseThrow()).toList());
        assertEquals(List.of(1, 2, 2, 2, 2, 2),
                groups.stream().map(EmailGroup::getNofMessages).toList());
    }

    private static List<EmailHeader> boundaryHeadersDescending() {
        ZonedDateTime todayStart = ZonedDateTime.now(FIXED_CLOCK).toLocalDate().atStartOfDay(FIXED_CLOCK.getZone());
        ZonedDateTime weekStart = GroupedPeriod.getBeginningOfWeek(FIXED_CLOCK).atStartOfDay(FIXED_CLOCK.getZone());
        ZonedDateTime lastWeekStart = weekStart.minusWeeks(1);
        ZonedDateTime monthStart = GroupedPeriod.getBeginningOfMonth(FIXED_CLOCK).atStartOfDay(FIXED_CLOCK.getZone());
        ZonedDateTime lastThreeMonthsStart = monthStart.minusMonths(2);

        return List.of(
                header(1, todayStart.plusMinutes(2), "today latest"),
                header(2, todayStart, "today start"),
                header(3, todayStart.minusMinutes(1), "this week boundary"),
                header(4, weekStart, "this week exact"),
                header(5, weekStart.minusMinutes(1), "last week boundary"),
                header(6, lastWeekStart, "last week exact"),
                header(7, lastWeekStart.minusMinutes(1), "this month boundary"),
                header(8, monthStart, "this month exact"),
                header(9, monthStart.minusMinutes(1), "last three months boundary"),
                header(10, lastThreeMonthsStart, "last three months exact"),
                header(11, lastThreeMonthsStart.minusMinutes(1), "older boundary")
        );
    }

    private static EmailHeader header(int id, ZonedDateTime receivedAt, String subject) {
        ZonedDateTime sentAt = receivedAt.minusMinutes(5);
        return new EmailHeader(
                id,
                id,
                Collections.emptyList(),
                subject,
                sentAt,
                sentAt.toEpochSecond(),
                receivedAt,
                receivedAt.toEpochSecond(),
                subject,
                false,
                false,
                false
        );
    }
}
