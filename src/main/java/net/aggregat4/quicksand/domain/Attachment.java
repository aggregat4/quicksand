package net.aggregat4.quicksand.domain;

import io.helidon.common.http.MediaType;

public record Attachment(int id, String name, long sizeInBytes, MediaType mediaType) {
}
