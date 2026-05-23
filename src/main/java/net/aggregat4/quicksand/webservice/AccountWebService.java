package net.aggregat4.quicksand.webservice;

import io.helidon.http.HttpMediaType;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.AccountNotificationSummary;
import net.aggregat4.quicksand.domain.DraftsFolder;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailGroup;
import net.aggregat4.quicksand.domain.EmailGroupPage;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.Folder;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.OutboxFolder;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.Query;
import net.aggregat4.quicksand.domain.SearchFolder;
import net.aggregat4.quicksand.domain.SidebarFolderLink;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.MailboxSyncRecoveryService;
import net.aggregat4.quicksand.service.NotificationService;
import net.aggregat4.quicksand.service.OutboundMessageService;

public class AccountWebService implements HttpService {
  public static final int PAGE_SIZE = 100;
  private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");

  private static final PebbleTemplate accountTemplate =
      PebbleConfig.getEngine().getTemplate("templates/account.peb");
  private static final PebbleTemplate accountWithoutFoldersTemplate =
      PebbleConfig.getEngine().getTemplate("templates/account-nofolders.peb");
  private static final PebbleTemplate folderSettingsTemplate =
      PebbleConfig.getEngine().getTemplate("templates/folder-settings.peb");
  private static final PebbleTemplate syncStatusTemplate =
      PebbleConfig.getEngine().getTemplate("templates/sync-status.peb");
  private static final PebbleTemplate notificationsTemplate =
      PebbleConfig.getEngine().getTemplate("templates/partials/notifications.peb");
  private final FolderService folderService;
  private final AccountService accountService;
  private final AccountFolderMappingService accountFolderMappingService;

  private final EmailService emailService;
  private final DraftService draftService;
  private final OutboundMessageService outboundMessageService;
  private final MailboxSyncRecoveryService mailboxSyncRecoveryService;
  private final NotificationService notificationService;
  private final Clock clock;

  public AccountWebService(
      FolderService folderService,
      AccountService accountService,
      AccountFolderMappingService accountFolderMappingService,
      EmailService emailService,
      DraftService draftService,
      OutboundMessageService outboundMessageService,
      MailboxSyncRecoveryService mailboxSyncRecoveryService,
      NotificationService notificationService,
      Clock clock) {
    this.folderService = folderService;
    this.accountService = accountService;
    this.accountFolderMappingService = accountFolderMappingService;
    this.emailService = emailService;
    this.draftService = draftService;
    this.outboundMessageService = outboundMessageService;
    this.mailboxSyncRecoveryService = mailboxSyncRecoveryService;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  @Override
  public void routing(HttpRules rules) {
    rules.get("/{accountId}", this::getAccountHandler);
    rules.get("/{accountId}/folders/{folderId}", this::getFolderHandler);
    rules.get("/{accountId}/drafts", this::getDraftsHandler);
    rules.get("/{accountId}/outbox", this::getOutboxHandler);
    rules.get("/{accountId}/search", this::getSearchHandler);
    rules.get("/{accountId}/sync", this::getSyncStatusHandler);
    rules.get("/{accountId}/notifications", this::getNotificationsHandler);
    rules.post("/{accountId}/sync/retry", this::postSyncRetryHandler);
    rules.post("/{accountId}/sync/dismiss", this::postSyncDismissHandler);
    rules.post("/{accountId}/sync/abandon", this::postSyncAbandonHandler);
    rules.post("/{accountId}/sync/rollback", this::postSyncRollbackHandler);
    rules.post("/{accountId}/sync/reset", this::postSyncResetHandler);
    rules.get("/{accountId}/settings/folders", this::getFolderSettingsHandler);
    rules.post("/{accountId}/settings/folders", this::postFolderSettingsHandler);
    rules.post("/{accountId}/emails", this::emailCreationHandler);
  }

  private void getAccountHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Optional<Integer> selectedEmailId =
        request.query().first("selectedEmailId").map(Integer::parseInt);
    List<NamedFolder> folders = folderService.getFolders(accountId);
    if (redirectToFolderSetupWhenNeeded(response, accountId, folders)) {
      return;
    }
    PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
    if (!folders.isEmpty()) {
      NamedFolder firstFolder = folders.getFirst();
      notificationService.markFolderViewed(firstFolder.id());
      EmailPage emailPage =
          emailService.getMessages(
              firstFolder.id(),
              PAGE_SIZE,
              defaultOffsetReceivedTimestamp(pageParams),
              defaultOffsetMessageId(pageParams),
              pageParams.pageDirection(),
              pageParams.sortOrder());
      int messageCount = emailService.getMessageCount(accountId, firstFolder.id());
      Pagination pagination =
          new Pagination(
              Optional.empty(),
              Optional.empty(),
              pageParams,
              PAGE_SIZE,
              Optional.of(messageCount),
              emailPage.hasLeft(),
              emailPage.hasRight());
      renderAccount(
          response,
          accountId,
          firstFolder,
          emailPage,
          pagination,
          selectedEmailId,
          Optional.empty());
    } else {
      renderDraftsAccount(response, accountId, Optional.empty());
    }
  }

  private void getFolderHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    int folderId = RequestUtils.intPathParam(request, "folderId");
    Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
    Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
    PageParams pageParams = parseEmailPageParams(request);
    var selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
    NamedFolder folder = findFolder(folderId);
    notificationService.markFolderViewed(folderId);
    int messageCount = emailService.getMessageCount(accountId, folder.id());
    int effectivePageSize = isEndJump(request) ? terminalPageSize(messageCount) : PAGE_SIZE;
    EmailPage emailPage =
        emailService.getMessages(
            folder.id(),
            effectivePageSize,
            offsetReceivedTimestamp.orElse(defaultOffsetReceivedTimestamp(pageParams)),
            offsetMessageId.orElse(defaultOffsetMessageId(pageParams)),
            pageParams.pageDirection(),
            pageParams.sortOrder());
    Pagination pagination =
        new Pagination(
            offsetReceivedTimestamp,
            offsetMessageId,
            pageParams,
            effectivePageSize,
            Optional.of(messageCount),
            emailPage.hasLeft(),
            emailPage.hasRight());
    renderAccount(
        response, accountId, folder, emailPage, pagination, selectedEmailId, Optional.empty());
  }

  private void getDraftsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    Optional<Integer> selectedEmailId =
        request.query().first("selectedEmailId").map(Integer::parseInt);
    renderDraftsAccount(response, accountId, selectedEmailId);
  }

  private void getOutboxHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    Optional<Integer> selectedEmailId =
        request.query().first("selectedEmailId").map(Integer::parseInt);
    renderOutboxAccount(response, accountId, selectedEmailId);
  }

  private static PageParams parseEmailPageParams(ServerRequest request) {
    return new PageParams(
        request
            .query()
            .first("pageDirection")
            .map(PageDirection::valueOf)
            .orElse(PageDirection.RIGHT),
        request.query().first("sortOrder").map(SortOrder::valueOf).orElse(SortOrder.DESCENDING));
  }

  private static Optional<Integer> parseOffsetMessageid(ServerRequest request) {
    return request
        .query()
        .first("offsetMessageId")
        .filter(AccountWebService::hasNumericValue)
        .map(Integer::parseInt);
  }

  private static Optional<Long> parseOffsetReceivedTimestamp(ServerRequest request) {
    return request
        .query()
        .first("offsetReceivedTimestamp")
        .filter(AccountWebService::hasNumericValue)
        .map(Long::parseLong);
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

  private static int defaultOffsetMessageId(PageParams pageParams) {
    return switch (pageParams.sortOrder()) {
      case DESCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? Integer.MAX_VALUE : 0;
      case ASCENDING -> pageParams.pageDirection() == PageDirection.RIGHT ? 0 : Integer.MAX_VALUE;
    };
  }

  private void getSearchHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
    Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
    PageParams pageParams = parseEmailPageParams(request);
    var selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
    Optional<String> query =
        request.query().first("query").map(String::trim).filter(s -> !s.isBlank());
    if (query.isEmpty()) {
      response.status(303);
      response.headers().location(URI.create("/accounts/%s".formatted(accountId)));
      response.send();
      return;
    }
    SearchFolder searchFolder = new SearchFolder(new Query(query.get()));
    int resultCount = emailService.getSearchMessageCount(accountId, query.get());
    int effectivePageSize = isEndJump(request) ? terminalPageSize(resultCount) : PAGE_SIZE;
    EmailPage emailPage =
        emailService.searchMessages(
            accountId,
            query.get(),
            effectivePageSize,
            offsetReceivedTimestamp.orElse(defaultOffsetReceivedTimestamp(pageParams)),
            offsetMessageId.orElse(defaultOffsetMessageId(pageParams)),
            pageParams.pageDirection(),
            pageParams.sortOrder());
    Pagination pagination =
        new Pagination(
            offsetReceivedTimestamp,
            offsetMessageId,
            pageParams,
            effectivePageSize,
            Optional.of(resultCount),
            emailPage.hasLeft(),
            emailPage.hasRight());
    renderAccount(response, accountId, searchFolder, emailPage, pagination, selectedEmailId, query);
  }

  private void getFolderSettingsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "accountpage folder-settings-page");
    context.put("currentAccount", accountService.getAccount(accountId));
    context.put("accounts", accountService.getAccounts());
    context.put("mappingRows", accountFolderMappingService.getSetupRows(accountId));
    context.put("showConfirmAll", accountFolderMappingService.hasAutoDetectedMappings(accountId));
    context.put("syncStatus", emailService.getMailboxSyncStatus(accountId));
    context.put("required", request.query().first("required"));
    context.put("saved", request.query().first("saved").isPresent());
    context.put("error", request.query().first("error"));
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, folderSettingsTemplate));
  }

  private void getSyncStatusHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "accountpage sync-status-page");
    context.put("currentAccount", accountService.getAccount(accountId));
    context.put("accounts", accountService.getAccounts());
    context.put("syncStatus", emailService.getMailboxSyncStatus(accountId));
    context.put("mappingRows", accountFolderMappingService.getSetupRows(accountId));
    context.put("saved", request.query().first("saved").isPresent());
    context.put("error", request.query().first("error").orElse(null));
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, syncStatusTemplate));
  }

  private void postSyncRetryHandler(ServerRequest request, ServerResponse response) {
    handleSyncActionPost(request, response, "retry");
  }

  private void postSyncDismissHandler(ServerRequest request, ServerResponse response) {
    handleSyncActionPost(request, response, "dismiss");
  }

  private void postSyncAbandonHandler(ServerRequest request, ServerResponse response) {
    handleSyncActionPost(request, response, "abandon");
  }

  private void postSyncRollbackHandler(ServerRequest request, ServerResponse response) {
    handleSyncActionPost(request, response, "rollback");
  }

  private void postSyncResetHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    mailboxSyncRecoveryService.resetLocalMirror(accountId);
    ResponseUtils.redirectAfterPost(
        response, URI.create("/accounts/%s/sync?saved=true".formatted(accountId)));
  }

  private void handleSyncActionPost(
      ServerRequest request, ServerResponse response, String actionName) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, String> formParams = parseFormEncoded(request.content().as(String.class));
    String actionIdValue = formParams.get("actionId");
    if (actionIdValue == null || actionIdValue.isBlank()) {
      redirectSyncStatus(response, accountId, "missing_action");
      return;
    }
    int actionId = Integer.parseInt(actionIdValue);
    boolean updated =
        switch (actionName) {
          case "retry" -> mailboxSyncRecoveryService.retryNow(accountId, actionId);
          case "dismiss" -> mailboxSyncRecoveryService.dismiss(accountId, actionId);
          case "abandon" -> mailboxSyncRecoveryService.abandon(accountId, actionId);
          case "rollback" -> mailboxSyncRecoveryService.rollback(accountId, actionId);
          default -> false;
        };
    redirectSyncStatus(response, accountId, updated ? "saved" : "invalid_action");
  }

  private static void redirectSyncStatus(ServerResponse response, int accountId, String result) {
    ResponseUtils.redirectAfterPost(
        response, URI.create("/accounts/%s/sync?%s=true".formatted(accountId, result)));
  }

  private void postFolderSettingsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, String> formParams = readFormParams(request);
    try {
      if ("true".equals(formParams.get("confirm_auto_detected"))) {
        accountFolderMappingService.confirmAutoDetectedMappings(accountId);
        ResponseUtils.redirectAfterPost(
            response, URI.create("/accounts/%s/settings/folders?saved=true".formatted(accountId)));
        return;
      }
      Optional<FolderSpecialUse> createSpecialUse =
          Optional.ofNullable(formParams.get("create_special_use"))
              .filter(value -> !value.isBlank())
              .map(FolderSpecialUse::valueOf);
      if (createSpecialUse.isPresent()) {
        FolderSpecialUse specialUse = createSpecialUse.get();
        accountFolderMappingService.createRemoteFolderAndMap(
            accountId, specialUse, formParams.get("create_name_" + specialUse.name()));
      } else if (isFolderMappingSaveRequest(formParams)) {
        Map<FolderSpecialUse, Integer> selectedFolderIds = parseSelectedFolderIds(formParams);
        accountFolderMappingService.saveExistingFolderMappings(accountId, selectedFolderIds);
      } else {
        throw new IllegalArgumentException("Folder settings form submission was empty or invalid.");
      }
      ResponseUtils.redirectAfterPost(
          response, URI.create("/accounts/%s/settings/folders?saved=true".formatted(accountId)));
    } catch (Exception e) {
      ResponseUtils.redirectAfterPost(
          response,
          URI.create(
              "/accounts/%s/settings/folders?error=%s"
                  .formatted(
                      accountId,
                      java.net.URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8))));
    }
  }

  private static boolean isFolderMappingSaveRequest(Map<String, String> formParams) {
    if ("true".equals(formParams.get("save_mappings"))) {
      return true;
    }
    return formParams.keySet().stream().anyMatch(key -> key.startsWith("folder_"));
  }

  private static Map<FolderSpecialUse, Integer> parseSelectedFolderIds(
      Map<String, String> formParams) {
    Map<FolderSpecialUse, Integer> selectedFolderIds = new EnumMap<>(FolderSpecialUse.class);
    for (FolderSpecialUse specialUse : FolderSpecialUse.values()) {
      if (specialUse == FolderSpecialUse.INBOX) {
        continue;
      }
      String folderIdValue = formParams.get("folder_" + specialUse.name());
      if (folderIdValue != null && !folderIdValue.isBlank()) {
        selectedFolderIds.put(specialUse, Integer.parseInt(folderIdValue));
      }
    }
    return selectedFolderIds;
  }

  private static Map<String, String> readFormParams(ServerRequest request) {
    String body = request.content().as(String.class);
    if (body == null || body.isBlank()) {
      return Map.of();
    }
    return parseFormEncoded(body);
  }

  private void renderDraftsAccount(
      ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
    List<EmailHeader> draftHeaders = draftService.getDraftHeaders(accountId);
    List<Email> drafts =
        draftHeaders.stream()
            .map(header -> new Email(header, true, header.bodyExcerpt(), Collections.emptyList()))
            .toList();
    PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
    Pagination pagination =
        new Pagination(
            Optional.empty(),
            Optional.empty(),
            pageParams,
            draftHeaders.size(),
            Optional.empty(),
            false,
            false);
    renderAccount(
        response,
        accountId,
        new DraftsFolder(),
        new EmailPage(drafts, false, false, pageParams),
        pagination,
        selectedEmailId,
        Optional.empty());
  }

  private void renderOutboxAccount(
      ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
    List<EmailHeader> queuedHeaders = outboundMessageService.getQueuedHeaders(accountId);
    List<Email> queuedMessages =
        queuedHeaders.stream()
            .map(header -> new Email(header, true, header.bodyExcerpt(), Collections.emptyList()))
            .toList();
    PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
    Pagination pagination =
        new Pagination(
            Optional.empty(),
            Optional.empty(),
            pageParams,
            queuedHeaders.size(),
            Optional.empty(),
            false,
            false);
    renderAccount(
        response,
        accountId,
        new OutboxFolder(),
        new EmailPage(queuedMessages, false, false, pageParams),
        pagination,
        selectedEmailId,
        Optional.empty());
  }

  private void renderAccount(
      ServerResponse response,
      int accountId,
      Folder folder,
      EmailPage emailPage,
      Pagination pagination,
      Optional<Integer> selectedEmailId,
      Optional<String> query) {
    List<NamedFolder> folders = folderService.getFolders(accountId);
    List<EmailHeader> emailHeaders = emailPage.emails().stream().map(Email::header).toList();
    List<EmailGroup> emailGroups =
        query.isPresent()
            ? EmailGroup.createNoGroupEmailgroup(emailHeaders)
            : EmailGroup.createEmailGroups(
                emailHeaders, clock, pagination.pageParams().sortOrder());
    EmailGroupPage emailGroupPage = new EmailGroupPage(emailGroups, pagination);
    List<NamedFolder> mailboxNavigationFolders = mailboxNavigationFolders(folders);
    AccountNotificationSummary notificationSummary =
        notificationService.getAccountSummary(accountId);
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "accountpage");
    context.put("currentAccount", accountService.getAccount(accountId));
    context.put("accounts", accountService.getAccounts());
    context.put("moveFolders", mailboxNavigationFolders);
    context.put(
        "sidebarFolders",
        toSidebarFolders(accountId, mailboxNavigationFolders, folder, notificationSummary));
    context.put("notificationSummary", notificationSummary);
    putInboxNotificationContext(context, notificationSummary, folder, folders);
    context.put("emailGroupPage", emailGroupPage);
    context.put("syncStatus", emailService.getMailboxSyncStatus(accountId));
    context.put("currentFolder", folder);
    context.put("currentFolderIsDrafts", folder instanceof DraftsFolder);
    context.put("currentFolderIsOutbox", folder instanceof OutboxFolder);
    if (selectedEmailId.isPresent()) {
      context.put("selectedEmailId", selectedEmailId.get());
    }
    if (folder instanceof NamedFolder namedFolder) {
      context.put("currentNamedFolderId", namedFolder.id());
    }
    boolean messageListLiveUpdates =
        folder instanceof NamedFolder && query.isEmpty() && isAtListHead(pagination);
    context.put("messageListLiveUpdates", messageListLiveUpdates);
    if (messageListLiveUpdates) {
      emailGroupPage
          .getFirstEmailHeader()
          .ifPresentOrElse(
              header -> {
                context.put("listCursorReceived", header.receivedDateTimeEpochSeconds());
                context.put("listCursorMessageId", header.id());
              },
              () -> {
                context.put("listCursorReceived", 0L);
                context.put("listCursorMessageId", 0);
              });
    }
    context.put("currentQuery", query);
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, accountTemplate));
  }

  private void getNotificationsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Optional<Integer> currentFolderId =
        request.query().first("folderId").flatMap(AccountWebService::parseOptionalInt);
    List<NamedFolder> folders = folderService.getFolders(accountId);
    AccountNotificationSummary notificationSummary =
        notificationService.getAccountSummary(accountId);
    Folder currentFolder =
        currentFolderId
            .flatMap(
                folderId -> folders.stream().filter(folder -> folder.id() == folderId).findFirst())
            .map(folder -> (Folder) folder)
            .orElse(new DraftsFolder());
    Map<String, Object> context = new HashMap<>();
    context.put(
        "sidebarFolders",
        toSidebarFolders(
            accountId, mailboxNavigationFolders(folders), currentFolder, notificationSummary));
    context.put("notificationSummary", notificationSummary);
    putInboxNotificationContext(context, notificationSummary, currentFolder, folders);
    Optional<Long> listCursorReceived =
        request.query().first("listCursorReceived").flatMap(AccountWebService::parseOptionalLong);
    Optional<Integer> listCursorMessageId =
        request.query().first("listCursorMessageId").flatMap(AccountWebService::parseOptionalInt);
    if (currentFolderId.isPresent()
        && listCursorReceived.isPresent()
        && listCursorMessageId.isPresent()) {
      List<EmailHeader> newMessageHeaders =
          emailService.getMessagesNewerThan(
              currentFolderId.get(), listCursorReceived.get(), listCursorMessageId.get(), 20);
      if (!newMessageHeaders.isEmpty()) {
        context.put(
            "newMessageGroups",
            EmailGroup.createEmailGroups(newMessageHeaders, clock, SortOrder.DESCENDING));
      }
      context.put("currentFolderIsDrafts", false);
      context.put("currentFolderIsOutbox", false);
      context.put("currentQuery", Optional.empty());
    }
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, notificationsTemplate));
  }

  private static Optional<Integer> parseOptionalInt(String value) {
    try {
      return Optional.of(Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Optional<Long> parseOptionalLong(String value) {
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private boolean isAtListHead(Pagination pagination) {
    return pagination.pageParams().sortOrder() == SortOrder.DESCENDING
        && pagination.pageParams().pageDirection() == PageDirection.RIGHT
        && pagination.receivedDateOffsetInSeconds().isEmpty()
        && pagination.messageIdOffset().isEmpty();
  }

  private void putInboxNotificationContext(
      Map<String, Object> context,
      AccountNotificationSummary summary,
      Folder currentFolder,
      List<NamedFolder> folders) {
    Optional<NamedFolder> inbox =
        folders.stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst();
    if (inbox.isEmpty()) {
      context.put("showInboxStrip", false);
      context.put("inboxStripLinked", false);
      context.put("inboxStripMessage", "");
      return;
    }
    Optional<Integer> currentFolderId =
        currentFolder instanceof NamedFolder namedFolder
            ? Optional.of(namedFolder.id())
            : Optional.empty();
    NotificationService.InboxNotification notification =
        notificationService.inboxNotification(summary, currentFolderId, inbox.get());
    context.put("showInboxStrip", notification.show());
    context.put("inboxStripLinked", notification.linked());
    context.put("inboxStripMessage", notification.message());
  }

  private List<SidebarFolderLink> toSidebarFolders(
      int accountId,
      List<NamedFolder> folders,
      Folder currentFolder,
      AccountNotificationSummary notificationSummary) {
    List<SidebarFolderLink> sidebarFolders =
        folders.stream()
            .map(
                folder ->
                    new SidebarFolderLink(
                        folder.name(),
                        "/accounts/%s/folders/%s".formatted(accountId, folder.id()),
                        currentFolder instanceof NamedFolder namedFolder
                            && namedFolder.id() == folder.id(),
                        notificationSummary.unreadCount(folder.id()),
                        folder.id()))
            .toList();
    List<SidebarFolderLink> links = new java.util.ArrayList<>(sidebarFolders);
    links.add(
        new SidebarFolderLink(
            "Outbox",
            "/accounts/%s/outbox".formatted(accountId),
            currentFolder instanceof OutboxFolder));
    links.add(
        new SidebarFolderLink(
            "Drafts",
            "/accounts/%s/drafts".formatted(accountId),
            currentFolder instanceof DraftsFolder));
    return links;
  }

  private List<NamedFolder> mailboxNavigationFolders(List<NamedFolder> folders) {
    return folders.stream()
        .filter(folder -> folder.specialUse() != FolderSpecialUse.DRAFTS)
        .toList();
  }

  private void emailCreationHandler(ServerRequest request, ServerResponse response) {
    boolean redirect = Boolean.parseBoolean(request.query().first("redirect").orElse("true"));
    Optional<Integer> replyEmailId = request.query().first("replyEmail").map(Integer::valueOf);
    Optional<Integer> forwardEmailId = request.query().first("forwardEmail").map(Integer::valueOf);
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    Optional<Integer> newEmailId =
        replyEmailId.isPresent()
            ? draftService
                .createReplyDraft(accountId, replyEmailId.get())
                .map(net.aggregat4.quicksand.domain.Draft::id)
            : forwardEmailId.isPresent()
                ? draftService
                    .createForwardDraft(accountId, forwardEmailId.get())
                    .map(net.aggregat4.quicksand.domain.Draft::id)
                : Optional.of(draftService.createDraft(accountId).id());
    if (newEmailId.isEmpty()) {
      response.status(404);
      response.send();
      return;
    }
    // a client can post a request for a new email with or without a redirect. In the latter case we
    // return the
    // composer location as the result and the client has to take care of going there himself
    if (redirect) {
      ResponseUtils.redirectAfterPost(response, getComposerLocation(newEmailId.get()));
    } else {
      response.status(200);
      response.send(getComposerLocation(newEmailId.get()).toString());
    }
  }

  private static URI getComposerLocation(int newEmailId) {
    return URI.create("/emails/%s/composer".formatted(newEmailId));
  }

  private NamedFolder findFolder(int folderId) {
    return folderService.getFolder(folderId);
  }

  private boolean redirectToFolderSetupWhenNeeded(ServerResponse response, int accountId) {
    return redirectToFolderSetupWhenNeeded(
        response, accountId, folderService.getFolders(accountId));
  }

  private boolean redirectToFolderSetupWhenNeeded(
      ServerResponse response, int accountId, List<NamedFolder> folders) {
    if (folders.isEmpty() || accountFolderMappingService.hasRequiredMappings(accountId)) {
      return false;
    }
    ResponseUtils.redirectAfterPost(
        response, URI.create("/accounts/%s/settings/folders".formatted(accountId)));
    return true;
  }

  private static Map<String, String> parseFormEncoded(String body) {
    Map<String, String> params = new HashMap<>();
    if (body == null || body.isBlank()) {
      return params;
    }
    for (String pair : body.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      String[] keyValue = pair.split("=", 2);
      String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
      String value =
          keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
      params.put(key, value);
    }
    return params;
  }
}
