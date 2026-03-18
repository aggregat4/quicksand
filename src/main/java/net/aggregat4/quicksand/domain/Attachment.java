package net.aggregat4.quicksand.domain;

import io.helidon.http.HttpMediaType;

public record Attachment(int id, String name, long sizeInBytes, HttpMediaType mediaType) {
}
