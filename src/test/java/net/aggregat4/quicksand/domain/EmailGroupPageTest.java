package net.aggregat4.quicksand.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class EmailGroupPageTest {
  @Test
  void paginationStatusTooltipIncludesTotalDateRangeAndFirstPage() {
    EmailHeader first = header(1, ZonedDateTime.parse("2026-05-17T10:00:00+02:00[Europe/Berlin]"));
    EmailHeader last = header(2, ZonedDateTime.parse("2026-05-25T10:00:00+02:00[Europe/Berlin]"));
    Pagination pagination =
        new Pagination(
            Optional.empty(),
            Optional.empty(),
            new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING),
            100,
            Optional.of(273),
            false,
            true);
    EmailGroupPage page =
        new EmailGroupPage(
            List.of(new EmailGroup(List.of(first, last), GroupedPeriod.NONE)), pagination);

    assertEquals(
        "273 messages total · 17 May to 25 May · Page 1 of 3", page.paginationStatusTooltip());
    assertEquals(OptionalInt.of(1), pagination.currentPageNumber());
  }

  @Test
  void paginationStatusTooltipIncludesLastPageWhenAtEnd() {
    Pagination pagination =
        new Pagination(
            Optional.of(1L),
            Optional.of(42),
            new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING),
            73,
            Optional.of(273),
            true,
            false);
    EmailGroupPage page =
        new EmailGroupPage(
            List.of(new EmailGroup(List.of(header(42, ZonedDateTime.now())), GroupedPeriod.NONE)),
            pagination);

    String tooltip = page.paginationStatusTooltip();
    assertEquals(OptionalInt.of(3), pagination.currentPageNumber());
    assertEquals(3, pagination.totalPages());
    assertTrue(tooltip.contains("273 messages total"), tooltip);
    assertTrue(tooltip.contains("Page 3 of 3"), tooltip);
  }

  private static EmailHeader header(int id, ZonedDateTime receivedAt) {
    ZonedDateTime sentAt = receivedAt.minusMinutes(5);
    return new EmailHeader(
        id,
        id,
        Collections.emptyList(),
        "Subject",
        sentAt,
        sentAt.toEpochSecond(),
        receivedAt,
        receivedAt.toEpochSecond(),
        "Excerpt",
        true,
        false,
        false);
  }
}
