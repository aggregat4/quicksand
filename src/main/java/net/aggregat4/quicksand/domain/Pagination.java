package net.aggregat4.quicksand.domain;

public record Pagination(int from, int to, int total, int pageSize) {
}
