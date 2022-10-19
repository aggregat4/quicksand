package net.aggregat4.quicksand.domain;

import java.util.Optional;

public record Pagination(int from, int to, Optional<Integer> total, int pageSize) {
}
