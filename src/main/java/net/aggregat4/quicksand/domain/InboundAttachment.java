package net.aggregat4.quicksand.domain;

public record InboundAttachment(
    String name, String mediaType, String contentHash, BinaryContent content) {}
