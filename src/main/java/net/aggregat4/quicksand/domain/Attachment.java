package net.aggregat4.quicksand.domain;

import org.springframework.http.MediaType;

public record Attachment(int id, String name, long sizeInBytes, MediaType mediaType) {
}
