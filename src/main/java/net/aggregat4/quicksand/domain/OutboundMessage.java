package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.util.Optional;

public record OutboundMessage(
        int id,
        int accountId,
        Optional<Integer> sourceMessageId,
        String fromName,
        String fromAddress,
        String to,
        String cc,
        String bcc,
        String subject,
        String body,
        ZonedDateTime queuedAt,
        long queuedAtEpochSeconds) {
}
