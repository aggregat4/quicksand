package net.aggregat4.quicksand.webservice;

import io.helidon.http.HttpMediaType;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
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
import net.aggregat4.quicksand.domain.MessageReadState;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.OutboxFolder;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.SidebarFolderLink;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.NotificationService;
import net.aggregat4.quicksand.service.OutboundMessageService;

final class AccountPageRenderer {
  private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");
  private static final PebbleTemplate accountTemplate =
      PebbleConfig.getEngine().getTemplate("templates/account.peb");
  private static final PebbleTemplate notificationsTemplate =
      PebbleConfig.getEngine().getTemplate("templates/partials/notifications.peb");

  private final FolderService folderService;
  private final AccountService accountService;
  private final EmailService emailService;
  private final DraftService draftService;
  private final OutboundMessageService outboundMessageService;
  private final NotificationService notificationService;
  private final Clock clock;

  AccountPageRenderer(
      FolderService folderService,
      AccountService accountService,
      EmailService emailService,
      DraftService draftService,
      OutboundMessageService outboundMessageService,
      NotificationService notificationService,
      Clock clock) {
    this.folderService = folderService;
    this.accountService = accountService;
    this.emailService = emailService;
    this.draftService = draftService;
    this.outboundMessageService = outboundMessageService;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  void renderAccount(
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
    context.put("syncNeedsAttention", emailService.needsMailboxSyncAttention(accountId));
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

  void renderDrafts(ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
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

  void renderOutbox(ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
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

  void renderNotifications(ServerRequest request, ServerResponse response, int accountId) {
    Optional<Integer> currentFolderId =
        request.query().first("folderId").flatMap(AccountPageRenderer::parseOptionalInt);
    currentFolderId.ifPresent(notificationService::markFolderViewed);
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
        request.query().first("listCursorReceived").flatMap(AccountPageRenderer::parseOptionalLong);
    Optional<Integer> listCursorMessageId =
        request.query().first("listCursorMessageId").flatMap(AccountPageRenderer::parseOptionalInt);
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
    request
        .query()
        .first("visibleMessageIds")
        .flatMap(AccountPageRenderer::parseCommaSeparatedInts)
        .filter(ids -> !ids.isEmpty())
        .ifPresent(
            messageIds -> {
              List<MessageReadState> readStates =
                  emailService.getReadStatesForMessages(accountId, messageIds);
              if (!readStates.isEmpty()) {
                context.put("readStateUpdates", readStates);
              }
            });
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, notificationsTemplate));
  }

  private static boolean isAtListHead(Pagination pagination) {
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

  private static List<SidebarFolderLink> toSidebarFolders(
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
    List<SidebarFolderLink> links = new ArrayList<>(sidebarFolders);
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

  private static List<NamedFolder> mailboxNavigationFolders(List<NamedFolder> folders) {
    return folders.stream()
        .filter(folder -> folder.specialUse() != FolderSpecialUse.DRAFTS)
        .toList();
  }

  private static Optional<List<Integer>> parseCommaSeparatedInts(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      List<Integer> ids = new ArrayList<>();
      for (String part : value.split(",", -1)) {
        if (!part.isBlank()) {
          ids.add(Integer.valueOf(part.trim()));
        }
      }
      return ids.isEmpty() ? Optional.empty() : Optional.of(ids);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Optional<Integer> parseOptionalInt(String value) {
    try {
      return Optional.of(Integer.valueOf(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Optional<Long> parseOptionalLong(String value) {
    try {
      return Optional.of(Long.valueOf(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
