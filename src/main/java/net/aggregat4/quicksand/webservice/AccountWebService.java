package net.aggregat4.quicksand.webservice;

import io.helidon.http.HeaderNames;
import io.helidon.http.HttpMediaType;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.Query;
import net.aggregat4.quicksand.domain.SearchFolder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.MailboxSyncRecoveryService;
import net.aggregat4.quicksand.service.MailboxUpdateBroadcaster;
import net.aggregat4.quicksand.service.NotificationService;
import net.aggregat4.quicksand.service.OutboundMessageService;

public class AccountWebService implements HttpService {
  public static final int PAGE_SIZE = AccountMessagePagination.PAGE_SIZE;
  private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");
  private static final HttpMediaType TEXT_EVENT_STREAM =
      HttpMediaType.create("text/event-stream; charset=UTF-8");
  private static final long SSE_HEARTBEAT_SECONDS = 30L;

  private static final PebbleTemplate accountWithoutFoldersTemplate =
      PebbleConfig.getEngine().getTemplate("templates/account-nofolders.peb");
  private static final PebbleTemplate folderSettingsTemplate =
      PebbleConfig.getEngine().getTemplate("templates/folder-settings.peb");
  private static final PebbleTemplate settingsTemplate =
      PebbleConfig.getEngine().getTemplate("templates/settings.peb");
  private static final PebbleTemplate syncStatusTemplate =
      PebbleConfig.getEngine().getTemplate("templates/sync-status.peb");
  private final FolderService folderService;
  private final AccountService accountService;
  private final AccountFolderMappingService accountFolderMappingService;

  private final EmailService emailService;
  private final DraftService draftService;
  private final OutboundMessageService outboundMessageService;
  private final MailboxSyncRecoveryService mailboxSyncRecoveryService;
  private final NotificationService notificationService;
  private final MailboxUpdateBroadcaster mailboxUpdateBroadcaster;
  private final AccountPageRenderer pageRenderer;

  public AccountWebService(
      FolderService folderService,
      AccountService accountService,
      AccountFolderMappingService accountFolderMappingService,
      EmailService emailService,
      DraftService draftService,
      OutboundMessageService outboundMessageService,
      MailboxSyncRecoveryService mailboxSyncRecoveryService,
      NotificationService notificationService,
      MailboxUpdateBroadcaster mailboxUpdateBroadcaster,
      Clock clock) {
    this.folderService = folderService;
    this.accountService = accountService;
    this.accountFolderMappingService = accountFolderMappingService;
    this.emailService = emailService;
    this.draftService = draftService;
    this.outboundMessageService = outboundMessageService;
    this.mailboxSyncRecoveryService = mailboxSyncRecoveryService;
    this.notificationService = notificationService;
    this.mailboxUpdateBroadcaster = mailboxUpdateBroadcaster;
    this.pageRenderer =
        new AccountPageRenderer(
            folderService,
            accountService,
            emailService,
            draftService,
            outboundMessageService,
            notificationService,
            clock);
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
    rules.get("/{accountId}/events", this::getEventsHandler);
    rules.post("/{accountId}/sync/retry", this::postSyncRetryHandler);
    rules.post("/{accountId}/sync/dismiss", this::postSyncDismissHandler);
    rules.post("/{accountId}/sync/abandon", this::postSyncAbandonHandler);
    rules.post("/{accountId}/sync/rollback", this::postSyncRollbackHandler);
    rules.post("/{accountId}/sync/reset", this::postSyncResetHandler);
    rules.get("/{accountId}/settings", this::getSettingsHandler);
    rules.get("/{accountId}/settings/folders", this::getFolderSettingsHandler);
    rules.post("/{accountId}/settings/folders", this::postFolderSettingsHandler);
    rules.post("/{accountId}/emails", this::emailCreationHandler);
  }

  private void getAccountHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Optional<Integer> selectedEmailId = AccountMessagePagination.selectedEmailId(request);
    List<NamedFolder> folders = folderService.getFolders(accountId);
    if (redirectToFolderSetupWhenNeeded(response, accountId, folders)) {
      return;
    }
    if (!folders.isEmpty()) {
      NamedFolder firstFolder = folders.getFirst();
      AccountMessagePagination.MessageListResult messageList =
          AccountMessagePagination.loadFirstFolderPage(emailService, accountId, firstFolder.id());
      pageRenderer.renderAccount(
          response,
          accountId,
          firstFolder,
          messageList.emailPage(),
          messageList.pagination(),
          selectedEmailId,
          Optional.empty());
    } else {
      pageRenderer.renderDrafts(response, accountId, Optional.empty());
    }
  }

  private void getFolderHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    int folderId = RequestUtils.intPathParam(request, "folderId");
    AccountMessagePagination.PageRequest pageRequest =
        AccountMessagePagination.PageRequest.from(request);
    Optional<Integer> selectedEmailId = AccountMessagePagination.selectedEmailId(request);
    NamedFolder folder = findFolder(folderId);
    AccountMessagePagination.MessageListResult messageList =
        AccountMessagePagination.loadFolderPage(emailService, accountId, folder.id(), pageRequest);
    pageRenderer.renderAccount(
        response,
        accountId,
        folder,
        messageList.emailPage(),
        messageList.pagination(),
        selectedEmailId,
        Optional.empty());
  }

  private void getDraftsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    pageRenderer.renderDrafts(
        response, accountId, AccountMessagePagination.selectedEmailId(request));
  }

  private void getOutboxHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    pageRenderer.renderOutbox(
        response, accountId, AccountMessagePagination.selectedEmailId(request));
  }

  private void getSearchHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    if (redirectToFolderSetupWhenNeeded(response, accountId)) {
      return;
    }
    AccountMessagePagination.PageRequest pageRequest =
        AccountMessagePagination.PageRequest.from(request);
    Optional<Integer> selectedEmailId = AccountMessagePagination.selectedEmailId(request);
    Optional<String> query =
        request.query().first("query").map(String::trim).filter(s -> !s.isBlank());
    if (query.isEmpty()) {
      response.status(303);
      response.headers().location(URI.create("/accounts/%s".formatted(accountId)));
      response.send();
      return;
    }
    SearchFolder searchFolder = new SearchFolder(new Query(query.get()));
    AccountMessagePagination.MessageListResult messageList =
        AccountMessagePagination.loadSearchPage(emailService, accountId, query.get(), pageRequest);
    pageRenderer.renderAccount(
        response,
        accountId,
        searchFolder,
        messageList.emailPage(),
        messageList.pagination(),
        selectedEmailId,
        query);
  }

  private void getSettingsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "accountpage settings-page");
    context.put("currentAccount", accountService.getAccount(accountId));
    context.put("accounts", accountService.getAccounts());
    var syncStatus = emailService.getMailboxSyncStatus(accountId);
    context.put("syncStatus", syncStatus);
    context.put("syncNeedsAttention", syncStatus.needsAttention());
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, settingsTemplate));
  }

  private void getFolderSettingsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "accountpage folder-settings-page");
    context.put("currentAccount", accountService.getAccount(accountId));
    context.put("accounts", accountService.getAccounts());
    context.put("mappingRows", accountFolderMappingService.getSetupRows(accountId));
    context.put("showConfirmAll", accountFolderMappingService.hasAutoDetectedMappings(accountId));
    var syncStatus = emailService.getMailboxSyncStatus(accountId);
    context.put("syncStatus", syncStatus);
    context.put("syncNeedsAttention", syncStatus.needsAttention());
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
    var syncStatus = emailService.getMailboxSyncStatus(accountId);
    context.put("syncStatus", syncStatus);
    context.put("syncNeedsAttention", syncStatus.needsAttention());
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

  private void getNotificationsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    pageRenderer.renderNotifications(request, response, accountId);
  }

  private void getEventsHandler(ServerRequest request, ServerResponse response) {
    int accountId = RequestUtils.intPathParam(request, "accountId");
    response.headers().contentType(TEXT_EVENT_STREAM);
    response.headers().add(HeaderNames.CACHE_CONTROL, "no-cache");
    response.headers().add(HeaderNames.CONNECTION, "keep-alive");

    BlockingQueue<Object> queue = new ArrayBlockingQueue<>(8);
    AutoCloseable subscription = mailboxUpdateBroadcaster.subscribe(accountId, queue);
    OutputStream outputStream = response.outputStream();
    try {
      if (!SseIo.writeComment(outputStream, "connected")) {
        return;
      }
      while (!Thread.currentThread().isInterrupted()) {
        Object token = queue.poll(SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        if (token == MailboxUpdateBroadcaster.wakeupToken()) {
          if (!SseIo.writeEvent(outputStream, "mailbox-updated", "{}")) {
            break;
          }
        } else if (!SseIo.writeComment(outputStream, "heartbeat")) {
          break;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      SseIo.closeQuietly(outputStream);
      try {
        subscription.close();
      } catch (Exception ignored) {
        // Best-effort unsubscribe.
      }
    }
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
    for (String pair : body.split("&", -1)) {
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
