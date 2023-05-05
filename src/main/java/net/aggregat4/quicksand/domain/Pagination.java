package net.aggregat4.quicksand.domain;

import java.util.Optional;

public record Pagination(
        Optional<Long> receivedDateOffsetInSeconds,
        Optional<Integer> messageIdOffset,
        PageParams pageParams,
        int pageSize,
        Optional<Integer> totalMessageCount) {
}
