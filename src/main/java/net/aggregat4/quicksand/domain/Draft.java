package net.aggregat4.quicksand.domain;

import java.util.Optional;

public record Draft(
        int id,
        int accountId,
        DraftType type,
        Optional<Integer> sourceMessageId,
        String to,
        String cc,
        String bcc,
        String subject,
        String body,
        boolean queued) {

    public Draft withContent(String to, String cc, String bcc, String subject, String body) {
        return new Draft(id, accountId, type, sourceMessageId, to, cc, bcc, subject, body, queued);
    }

    public Draft markQueued() {
        return new Draft(id, accountId, type, sourceMessageId, to, cc, bcc, subject, body, true);
    }
}
