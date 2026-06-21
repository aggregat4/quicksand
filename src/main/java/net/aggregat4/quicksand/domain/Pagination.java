package net.aggregat4.quicksand.domain;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalInt;
import net.aggregat4.quicksand.time.ApplicationClock;

public record Pagination(
    Optional<Long> receivedDateOffsetInSeconds,
    Optional<Integer> messageIdOffset,
    PageParams pageParams,
    int pageSize,
    Optional<Integer> totalMessageCount,
    boolean hasLeft,
    boolean hasRight,
    Optional<SearchOrder> searchOrder) {
  private static final DateTimeFormatter OFFSET_FORMATTER =
      DateTimeFormatter.ofPattern("dd LLL HH:mm");
  private static final int STANDARD_PAGE_SIZE = 100;

  public Pagination(
      Optional<Long> receivedDateOffsetInSeconds,
      Optional<Integer> messageIdOffset,
      PageParams pageParams,
      int pageSize,
      Optional<Integer> totalMessageCount,
      boolean hasLeft,
      boolean hasRight) {
    this(
        receivedDateOffsetInSeconds,
        messageIdOffset,
        pageParams,
        pageSize,
        totalMessageCount,
        hasLeft,
        hasRight,
        Optional.empty());
  }

  public Optional<String> formattedReceivedDateOffset() {
    return receivedDateOffsetInSeconds
        .map(Instant::ofEpochSecond)
        .map(instant -> instant.atZone(ApplicationClock.zone()))
        .map(OFFSET_FORMATTER::format);
  }

  public int totalPages() {
    return totalMessageCount
        .map(
            total -> {
              if (total == 0) {
                return 1;
              }
              int remainder = total % STANDARD_PAGE_SIZE;
              return remainder == 0 ? total / STANDARD_PAGE_SIZE : (total / STANDARD_PAGE_SIZE) + 1;
            })
        .orElse(1);
  }

  public OptionalInt currentPageNumber() {
    if (totalMessageCount.isEmpty() || totalMessageCount.get() == 0) {
      return OptionalInt.of(1);
    }
    int totalPages = totalPages();
    if (totalPages <= 1) {
      return OptionalInt.of(1);
    }
    if (!hasLeft) {
      return OptionalInt.of(1);
    }
    if (!hasRight) {
      return OptionalInt.of(totalPages);
    }
    return OptionalInt.empty();
  }

  public String getSearchOrderString() {
    return searchOrder.map(SearchOrder::getParamString).orElse("");
  }
}
