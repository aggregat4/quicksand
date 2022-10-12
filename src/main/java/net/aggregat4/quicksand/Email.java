package net.aggregat4.quicksand;

import java.util.List;

public record Email(
        EmailHeader header,
        boolean plainText,
        String body,
        List<Attachment> attachments) {
}
