package net.aggregat4.quicksand.domain;

import org.springframework.util.MimeType;

public record Attachment(String name, long sizeInBytes, MimeType mimeType) {
}
