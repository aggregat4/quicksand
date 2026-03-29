package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public record EmailGroupPage(List<EmailGroup> groups, Pagination pagination) {
    private static final DateTimeFormatter RECEIVED_RANGE_FORMATTER = DateTimeFormatter.ofPattern("dd LLL HH:mm");

    public int getNofMessages() {
        return groups.stream().mapToInt(EmailGroup::getNofMessages).sum();
    }

    public Optional<EmailHeader> getFirstEmailHeader() {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        EmailGroup firstGroup = groups.getFirst();
        if (firstGroup.headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(firstGroup.headers().getFirst());
    }

    public Optional<EmailHeader> getLastEmailHeader() {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        EmailGroup lastGroup = groups.getLast();
        if (lastGroup.headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(lastGroup.headers().getLast());
    }

    public Optional<String> formattedReceivedDateRange() {
        Optional<EmailHeader> firstHeader = getFirstEmailHeader();
        Optional<EmailHeader> lastHeader = getLastEmailHeader();
        if (firstHeader.isEmpty() || lastHeader.isEmpty()) {
            return Optional.empty();
        }

        ZonedDateTime start = firstHeader.get().receivedDateTime();
        ZonedDateTime end = lastHeader.get().receivedDateTime();
        if (start.isAfter(end)) {
            ZonedDateTime tmp = start;
            start = end;
            end = tmp;
        }
        return Optional.of(RECEIVED_RANGE_FORMATTER.format(start) + " to " + RECEIVED_RANGE_FORMATTER.format(end));
    }
}
