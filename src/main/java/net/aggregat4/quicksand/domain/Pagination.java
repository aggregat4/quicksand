package net.aggregat4.quicksand.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public record Pagination(
        Optional<Long> receivedDateOffsetInSeconds,
        Optional<Integer> messageIdOffset,
        PageParams pageParams,
        int pageSize,
        Optional<Integer> totalMessageCount,
        boolean hasLeft,
        boolean hasRight) {
    private static final DateTimeFormatter OFFSET_FORMATTER = DateTimeFormatter.ofPattern("dd LLL HH:mm");

    public Optional<String> formattedReceivedDateOffset() {
        return receivedDateOffsetInSeconds
                .map(Instant::ofEpochSecond)
                .map(instant -> instant.atZone(ZoneId.systemDefault()))
                .map(OFFSET_FORMATTER::format);
    }
}
