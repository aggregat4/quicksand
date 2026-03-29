package net.aggregat4.quicksand.webservice;

import io.helidon.http.BadRequestException;
import io.helidon.http.HttpMediaType;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.DraftsFolder;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailGroup;
import net.aggregat4.quicksand.domain.EmailGroupPage;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.Folder;
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
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.OutboundMessageService;

import java.time.Clock;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AccountWebService implements HttpService {
    public static final int PAGE_SIZE = 100;
    private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");

    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");
    private static final PebbleTemplate accountWithoutFoldersTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account-nofolders.peb");
    private final FolderService folderService;
    private final AccountService accountService;

    private final EmailService emailService;
    private final DraftService draftService;
    private final OutboundMessageService outboundMessageService;
    private final Clock clock;

    public AccountWebService(FolderService folderService, AccountService accountService, EmailService emailService, DraftService draftService, OutboundMessageService outboundMessageService, Clock clock) {
        this.folderService = folderService;
        this.accountService = accountService;
        this.emailService = emailService;
        this.draftService = draftService;
        this.outboundMessageService = outboundMessageService;
        this.clock = clock;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/{accountId}", this::getAccountHandler);
        rules.get("/{accountId}/folders/{folderId}", this::getFolderHandler);
        rules.get("/{accountId}/drafts", this::getDraftsHandler);
        rules.get("/{accountId}/outbox", this::getOutboxHandler);
        rules.get("/{accountId}/search", this::getSearchHandler);
        rules.post("/{accountId}/emails", this::emailCreationHandler);
    }

    private void getAccountHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        List<NamedFolder> folders = folderService.getFolders(accountId);
        PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
        if (! folders.isEmpty()) {
            NamedFolder firstFolder = folders.getFirst();
            EmailPage emailPage = emailService.getMessages(
                    firstFolder.id(),
                    PAGE_SIZE,
                    defaultOffsetReceivedTimestamp(pageParams),
                    defaultOffsetMessageId(pageParams),
                    pageParams.pageDirection(),
                    pageParams.sortOrder());
            int messageCount = emailService.getMessageCount(accountId, firstFolder.id());
            Pagination pagination = new Pagination(Optional.empty(), Optional.empty(), pageParams, PAGE_SIZE, Optional.of(messageCount), emailPage.hasLeft(), emailPage.hasRight());
            renderAccount(response, accountId, firstFolder, emailPage, pagination, Optional.empty(), Optional.empty());
        } else {
            renderDraftsAccount(response, accountId, Optional.empty());
        }
    }

    private void getFolderHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        int folderId = RequestUtils.intPathParam(request, "folderId");
        Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
        Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
        PageParams pageParams = parseEmailPageParams(request);
        var selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
        NamedFolder folder = findFolder(folderId);
        int messageCount = emailService.getMessageCount(accountId, folder.id());
        int effectivePageSize = isEndJump(request) ? terminalPageSize(messageCount) : PAGE_SIZE;
        EmailPage emailPage = emailService.getMessages(
                folder.id(),
                effectivePageSize,
                offsetReceivedTimestamp.orElse(defaultOffsetReceivedTimestamp(pageParams)),
                offsetMessageId.orElse(defaultOffsetMessageId(pageParams)),
                pageParams.pageDirection(),
                pageParams.sortOrder());
        Pagination pagination = new Pagination(offsetReceivedTimestamp, offsetMessageId, pageParams, effectivePageSize, Optional.of(messageCount), emailPage.hasLeft(), emailPage.hasRight());
        renderAccount(response, accountId, folder, emailPage, pagination, selectedEmailId, Optional.empty());
    }

    private void getDraftsHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        Optional<Integer> selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
        renderDraftsAccount(response, accountId, selectedEmailId);
    }

    private void getOutboxHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        Optional<Integer> selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
        renderOutboxAccount(response, accountId, selectedEmailId);
    }

    private static PageParams parseEmailPageParams(ServerRequest request) {
        return new PageParams(
                request.query().first("pageDirection").map(PageDirection::valueOf).orElse(PageDirection.RIGHT),
                request.query().first("sortOrder").map(SortOrder::valueOf).orElse(SortOrder.DESCENDING));
    }

    private static Optional<Integer> parseOffsetMessageid(ServerRequest request) {
        return request.query().first("offsetMessageId")
                .filter(AccountWebService::hasNumericValue)
                .map(Integer::parseInt);
    }

    private static Optional<Long> parseOffsetReceivedTimestamp(ServerRequest request) {
        return request.query().first("offsetReceivedTimestamp")
                .filter(AccountWebService::hasNumericValue)
                .map(Long::parseLong);
    }

    private static boolean hasNumericValue(String value) {
        return !value.isBlank() && !"null".equalsIgnoreCase(value);
    }

    private static boolean isEndJump(ServerRequest request) {
        return request.query().first("pagePosition")
                .map("END"::equals)
                .orElse(false);
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
        Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
        Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
        PageParams pageParams = parseEmailPageParams(request);
        var selectedEmailId = request.query().first("selectedEmailId").map(Integer::parseInt);
        Optional<String> query = request.query().first("query").map(s -> s);
        if (query.isEmpty()) {
            throw new BadRequestException("Search URL must contain 'query' parameter");
        }
        SearchFolder searchFolder = new SearchFolder(new Query(query.get()));
        // TODO: implement search with paging
        Pagination pagination = new Pagination(offsetReceivedTimestamp, offsetMessageId, pageParams, PAGE_SIZE, Optional.empty(), false, false);
        renderAccount(response, accountId, searchFolder, new EmailPage(Collections.emptyList(), false, false, new PageParams(PageDirection.RIGHT, SortOrder.ASCENDING)), pagination, selectedEmailId, query);
    }

    private void renderDraftsAccount(ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
        List<EmailHeader> draftHeaders = draftService.getDraftHeaders(accountId);
        List<Email> drafts = draftHeaders.stream()
                .map(header -> new Email(header, true, header.bodyExcerpt(), Collections.emptyList()))
                .toList();
        PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
        Pagination pagination = new Pagination(Optional.empty(), Optional.empty(), pageParams, draftHeaders.size(), Optional.empty(), false, false);
        renderAccount(response, accountId, new DraftsFolder(), new EmailPage(drafts, false, false, pageParams), pagination, selectedEmailId, Optional.empty());
    }

    private void renderOutboxAccount(ServerResponse response, int accountId, Optional<Integer> selectedEmailId) {
        List<EmailHeader> queuedHeaders = outboundMessageService.getQueuedHeaders(accountId);
        List<Email> queuedMessages = queuedHeaders.stream()
                .map(header -> new Email(header, true, header.bodyExcerpt(), Collections.emptyList()))
                .toList();
        PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
        Pagination pagination = new Pagination(Optional.empty(), Optional.empty(), pageParams, queuedHeaders.size(), Optional.empty(), false, false);
        renderAccount(response, accountId, new OutboxFolder(), new EmailPage(queuedMessages, false, false, pageParams), pagination, selectedEmailId, Optional.empty());
    }

    private void renderAccount(ServerResponse response, int accountId, Folder folder, EmailPage emailPage, Pagination pagination, Optional<Integer> selectedEmailId, Optional<String> query) {
        List<NamedFolder> folders = folderService.getFolders(accountId);
        List<EmailHeader> emailHeaders = emailPage.emails().stream().map(Email::header).toList();
        List<EmailGroup> emailGroups = query.isPresent()
                ? EmailGroup.createNoGroupEmailgroup(emailHeaders)
                : EmailGroup.createEmailGroups(emailHeaders, clock, pagination.pageParams().sortOrder());
        EmailGroupPage emailGroupPage = new EmailGroupPage(emailGroups, pagination);
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", accountService.getAccount(accountId));
        context.put("accounts", accountService.getAccounts());
        context.put("moveFolders", folders);
        context.put("sidebarFolders", toSidebarFolders(accountId, folders, folder));
        context.put("emailGroupPage", emailGroupPage);
        context.put("currentFolder", folder);
        context.put("currentFolderIsDrafts", folder instanceof DraftsFolder);
        context.put("currentFolderIsOutbox", folder instanceof OutboxFolder);
        if (selectedEmailId.isPresent()) {
            context.put("selectedEmailId", selectedEmailId.get());
        }
        if (query.isPresent()) {
            context.put("currentQuery", query.get());
        }
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, accountTemplate));
    }

    private List<SidebarFolderLink> toSidebarFolders(int accountId, List<NamedFolder> folders, Folder currentFolder) {
        List<SidebarFolderLink> sidebarFolders = folders.stream()
                .map(folder -> new SidebarFolderLink(
                        folder.name(),
                        "/accounts/%s/folders/%s".formatted(accountId, folder.id()),
                        currentFolder instanceof NamedFolder namedFolder && namedFolder.id() == folder.id()))
                .toList();
        List<SidebarFolderLink> links = new java.util.ArrayList<>(sidebarFolders);
        links.add(new SidebarFolderLink(
                "Outbox",
                "/accounts/%s/outbox".formatted(accountId),
                currentFolder instanceof OutboxFolder));
        links.add(new SidebarFolderLink(
                "Drafts",
                "/accounts/%s/drafts".formatted(accountId),
                currentFolder instanceof DraftsFolder));
        return links;
    }

    private void renderAccountWithoutFolders(ServerResponse response, int accountId) {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", accountService.getAccount(accountId));
        context.put("accounts", accountService.getAccounts());
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, accountWithoutFoldersTemplate));
    }

    private void emailCreationHandler(ServerRequest request, ServerResponse response) {
        boolean redirect = Boolean.parseBoolean(request.query().first("redirect").orElse("true"));
        Optional<Integer> replyEmailId = request.query().first("replyEmail").map(Integer::valueOf);
        Optional<Integer> forwardEmailId = request.query().first("forwardEmail").map(Integer::valueOf);
        int accountId = RequestUtils.intPathParam(request, "accountId");
        Optional<Integer> newEmailId = replyEmailId.isPresent()
                ? draftService.createReplyDraft(accountId, replyEmailId.get()).map(net.aggregat4.quicksand.domain.Draft::id)
                : forwardEmailId.isPresent()
                ? draftService.createForwardDraft(accountId, forwardEmailId.get()).map(net.aggregat4.quicksand.domain.Draft::id)
                : Optional.of(draftService.createDraft(accountId).id());
        if (newEmailId.isEmpty()) {
            response.status(404);
            response.send();
            return;
        }
        // a client can post a request for a new email with or without a redirect. In the latter case we return the
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

}
