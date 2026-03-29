package net.aggregat4.quicksand.domain;

import io.helidon.http.HttpMediaType;

import java.util.Optional;

public record StoredAttachment(
        int id,
        Optional<Integer> draftId,
        Optional<Integer> messageId,
        Optional<Integer> outboundMessageId,
        String name,
        long sizeInBytes,
        HttpMediaType mediaType,
        String contentHash,
        byte[] content) {

    public Attachment toAttachment() {
        return new Attachment(id, name, sizeInBytes, mediaType);
    }
}
