package net.aggregat4.quicksand.webservice;

import io.helidon.webserver.http.ServerRequest;
import java.util.Optional;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.SearchOrder;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.service.EmailService;

final class AccountMessagePagination {
  static final int PAGE_SIZE = 100;

  private AccountMessagePagination() {}

  record PageRequest(
      Optional<Long> offsetReceivedTimestamp,
      Optional<Integer> offsetMessageId,
      PageParams pageParams,
      Optional<SearchOrder> searchOrder,
      Optional<Double> offsetRank,
      boolean endJump) {

    static PageRequest from(ServerRequest request) {
      Optional<SearchOrder> searchOrder =
          request.query().first("searchOrder").map(SearchOrder::valueOf);
      return new PageRequest(
          parseOffsetReceivedTimestamp(request),
          parseOffsetMessageId(request),
          parsePageParams(request, searchOrder),
          searchOrder,
          parseOffsetRank(request),
          isEndJump(request));
    }

    static PageRequest firstPage() {
      return new PageRequest(
          Optional.empty(),
          Optional.empty(),
          new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING),
          Optional.empty(),
          Optional.empty(),
          false);
    }
  }

  record MessageListResult(EmailPage emailPage, Pagination pagination) {}

  static Optional<Integer> selectedEmailId(ServerRequest request) {
    return request.query().first("selectedEmailId").map(Integer::parseInt);
  }

  static MessageListResult loadFolderPage(
      EmailService emailService, int accountId, int folderId, PageRequest request) {
    int messageCount = emailService.getMessageCount(accountId, folderId);
    int effectivePageSize = request.endJump() ? terminalPageSize(messageCount) : PAGE_SIZE;
    EmailPage emailPage =
        emailService.getMessages(
            folderId,
            effectivePageSize,
            request
                .offsetReceivedTimestamp()
                .orElse(defaultOffsetReceivedTimestamp(request.pageParams())),
            request.offsetMessageId().orElse(defaultOffsetMessageId(request.pageParams())),
            request.pageParams().pageDirection(),
            request.pageParams().sortOrder());
    Pagination pagination =
        new Pagination(
            request.offsetReceivedTimestamp(),
            request.offsetMessageId(),
            request.pageParams(),
            effectivePageSize,
            Optional.of(messageCount),
            emailPage.hasLeft(),
            emailPage.hasRight());
    return new MessageListResult(emailPage, pagination);
  }

  static MessageListResult loadSearchPage(
      EmailService emailService, int accountId, String query, PageRequest request) {
    SearchOrder searchOrder = request.searchOrder().orElse(SearchOrder.NEWEST);
    int resultCount = emailService.getSearchMessageCount(accountId, query);
    int effectivePageSize = request.endJump() ? terminalPageSize(resultCount) : PAGE_SIZE;
    long dateCursor =
        request
            .offsetReceivedTimestamp()
            .orElse(
                defaultOffsetReceivedTimestamp(searchOrder, request.pageParams().pageDirection()));
    int messageIdCursor =
        request.offsetMessageId().orElse(defaultOffsetMessageId(request.pageParams()));
    EmailPage emailPage =
        emailService.searchMessages(
            accountId,
            query,
            effectivePageSize,
            searchOrder,
            request.pageParams().pageDirection(),
            request.offsetRank(),
            dateCursor,
            messageIdCursor,
            request.endJump());
    Pagination pagination =
        new Pagination(
            request.offsetReceivedTimestamp(),
            request.offsetMessageId(),
            request.pageParams(),
            effectivePageSize,
            Optional.of(resultCount),
            emailPage.hasLeft(),
            emailPage.hasRight(),
            Optional.of(searchOrder));
    return new MessageListResult(emailPage, pagination);
  }

  static MessageListResult loadFirstFolderPage(
      EmailService emailService, int accountId, int folderId) {
    PageRequest request = PageRequest.firstPage();
    EmailPage emailPage =
        emailService.getMessages(
            folderId,
            PAGE_SIZE,
            defaultOffsetReceivedTimestamp(request.pageParams()),
            defaultOffsetMessageId(request.pageParams()),
            request.pageParams().pageDirection(),
            request.pageParams().sortOrder());
    int messageCount = emailService.getMessageCount(accountId, folderId);
    Pagination pagination =
        new Pagination(
            Optional.empty(),
            Optional.empty(),
            request.pageParams(),
            PAGE_SIZE,
            Optional.of(messageCount),
            emailPage.hasLeft(),
            emailPage.hasRight());
    return new MessageListResult(emailPage, pagination);
  }

  private static PageParams parsePageParams(
      ServerRequest request, Optional<SearchOrder> searchOrder) {
    SortOrder sortOrder =
        searchOrder
            .map(SearchOrder::toSortOrder)
            .orElseGet(
                () ->
                    request
                        .query()
                        .first("sortOrder")
                        .map(SortOrder::valueOf)
                        .orElse(SortOrder.DESCENDING));
    return new PageParams(
        request
            .query()
            .first("pageDirection")
            .map(PageDirection::valueOf)
            .orElse(PageDirection.RIGHT),
        sortOrder);
  }

  private static Optional<Integer> parseOffsetMessageId(ServerRequest request) {
    return request
        .query()
        .first("offsetMessageId")
        .filter(AccountMessagePagination::hasNumericValue)
        .map(Integer::parseInt);
  }

  private static Optional<Long> parseOffsetReceivedTimestamp(ServerRequest request) {
    return request
        .query()
        .first("offsetReceivedTimestamp")
        .filter(AccountMessagePagination::hasNumericValue)
        .map(Long::parseLong);
  }

  private static Optional<Double> parseOffsetRank(ServerRequest request) {
    return request
        .query()
        .first("offsetRank")
        .filter(AccountMessagePagination::hasNumericValue)
        .map(Double::parseDouble);
  }

  private static boolean hasNumericValue(String value) {
    return !value.isBlank() && !"null".equalsIgnoreCase(value);
  }

  private static boolean isEndJump(ServerRequest request) {
    return request.query().first("pagePosition").map("END"::equals).orElse(false);
  }

  private static int terminalPageSize(int totalMessageCount) {
    if (totalMessageCount == 0) {
      return PAGE_SIZE;
    }
    int remainder = totalMessageCount % PAGE_SIZE;
    return remainder == 0 ? PAGE_SIZE : remainder;
  }

  private static long defaultOffsetReceivedTimestamp(PageParams pageParams) {
    return switch (pageParams.sortOrder()) {
      case DESCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? Long.MAX_VALUE : 0L;
      case ASCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? 0L : Long.MAX_VALUE;
    };
  }

  private static long defaultOffsetReceivedTimestamp(
      SearchOrder searchOrder, PageDirection pageDirection) {
    return switch (searchOrder) {
      case NEWEST, BEST_MATCH -> pageDirection == PageDirection.RIGHT ? Long.MAX_VALUE : 0L;
      case OLDEST -> pageDirection == PageDirection.RIGHT ? 0L : Long.MAX_VALUE;
    };
  }

  private static int defaultOffsetMessageId(PageParams pageParams) {
    return switch (pageParams.sortOrder()) {
      case DESCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? Integer.MAX_VALUE : 0;
      case ASCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? 0 : Integer.MAX_VALUE;
    };
  }
}
