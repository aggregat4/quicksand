package net.aggregat4.quicksand;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public record EmailHeader(
        int id,
        Actor sender,
        String subject,
        ZonedDateTime receivedDate,
        String bodyExcerpt,
        boolean starred,
        boolean attachment,
        boolean read) {
    private static DateTimeFormatter currentYearFormatter = DateTimeFormatter.ofPattern("dd LLL");

    public String formattedReceivedDate() {
        return currentYearFormatter.format(receivedDate.toLocalDate());
    }
}
