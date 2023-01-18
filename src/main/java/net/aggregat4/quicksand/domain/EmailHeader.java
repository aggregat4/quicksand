package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

public record EmailHeader(
        int id,
        Actor sender,
        Actor recipient,
        String subject,
        ZonedDateTime sentDateTime,
        ZonedDateTime receivedDateTime,
        String bodyExcerpt,
        boolean starred,
        boolean attachment,
        boolean read) {
    private static DateTimeFormatter currentYearFormatter = DateTimeFormatter.ofPattern("dd LLL");
    private static DateTimeFormatter longDateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    public String shortFormattedReceivedDate() {
        return currentYearFormatter.format(receivedDateTime.toLocalDate());
    }

    public String longFormattedReceivedDate() {
        return longDateFormatter.format(receivedDateTime.toLocalDateTime());
    }

    public String shortFormattedSentDate() {
        return currentYearFormatter.format(sentDateTime.toLocalDate());
    }

    public String longFormattedSentDate() {
        return longDateFormatter.format(sentDateTime.toLocalDateTime());
    }
}
