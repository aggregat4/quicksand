package net.aggregat4.quicksand.domain;

public record Pagination(long receivedDateOffsetInSeconds, PageParams pageParams, int pageSize) {
}
