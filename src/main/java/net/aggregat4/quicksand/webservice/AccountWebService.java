package net.aggregat4.quicksand.webservice;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.MediaType;
import io.helidon.webserver.BadRequestException;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailGroup;
import net.aggregat4.quicksand.domain.EmailGroupPage;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.Folder;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.Query;
import net.aggregat4.quicksand.domain.SearchFolder;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AccountWebService implements Service {
    public static final int PAGE_SIZE = 100;

    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");
    private static final PebbleTemplate accountWithoutFoldersTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account-nofolders.peb");
    private final FolderService folderService;
    private final AccountService accountService;

    private final EmailService emailService;

    public AccountWebService(FolderService folderService, AccountService accountService, EmailService emailService) {
        this.folderService = folderService;
        this.accountService = accountService;
        this.emailService = emailService;
    }

    @Override
    public void update(Routing.Rules rules) {
        rules.get("/{accountId}", this::getAccountHandler);
        rules.get("/{accountId}/folders/{folderId}", this::getFolderHandler);
        rules.get("/{accountId}/search", this::getSearchHandler);
        rules.post("/{accountId}/emails", this::emailCreationHandler);
    }

    private void getAccountHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        List<NamedFolder> folders = folderService.getFolders(accountId);
        PageParams pageParams = new PageParams(PageDirection.RIGHT, SortOrder.DESCENDING);
        if (! folders.isEmpty()) {
            EmailPage emailPage = emailService.getMessages(accountId, folders.get(0).id(), Long.MAX_VALUE, Integer.MAX_VALUE, pageParams.pageDirection(), pageParams.sortOrder());
            int messageCount = emailService.getMessageCount(accountId, folders.get(0).id());
            Pagination pagination = new Pagination(Optional.empty(), Optional.empty(), pageParams, PAGE_SIZE, Optional.of(messageCount), emailPage.hasLeft(), emailPage.hasRight());
            renderAccount(response, accountId, folders.get(0), emailPage, pagination, Optional.empty(), Optional.empty());
        } else {
            renderAccountWithoutFolders(response, accountId);
        }
    }

    private void getFolderHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        int folderId = RequestUtils.intPathParam(request, "folderId");
        Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
        Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
        PageParams pageParams = parseEmailPageParams(request);
        var selectedEmailId = request.queryParams().first("selectedEmailId").map(Integer::parseInt);
        NamedFolder folder = findFolder(folderId);
        EmailPage emailPage = emailService.getMessages(folder.id(), PAGE_SIZE, offsetReceivedTimestamp.orElse(Long.MAX_VALUE), offsetMessageId.orElse(Integer.MAX_VALUE), pageParams.pageDirection(), pageParams.sortOrder());
        int messageCount = emailService.getMessageCount(accountId, folder.id());
        Pagination pagination = new Pagination(offsetReceivedTimestamp, offsetMessageId, pageParams, PAGE_SIZE, Optional.of(messageCount), emailPage.hasLeft(), emailPage.hasRight());
        renderAccount(response, accountId, folder, emailPage, pagination, selectedEmailId, Optional.empty());
    }

    private static PageParams parseEmailPageParams(ServerRequest request) {
        return new PageParams(
                request.queryParams().first("pageDirection").map(PageDirection::valueOf).orElse(PageDirection.RIGHT),
                request.queryParams().first("sortOrder").map(SortOrder::valueOf).orElse(SortOrder.DESCENDING));
    }

    private static Optional<Integer> parseOffsetMessageid(ServerRequest request) {
        return request.queryParams().first("offsetMessageId").map(Integer::parseInt);
    }

    private static Optional<Long> parseOffsetReceivedTimestamp(ServerRequest request) {
        return request.queryParams().first("offsetReceivedTimestamp").map(Long::parseLong);
    }

    private void getSearchHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        Optional<Long> offsetReceivedTimestamp = parseOffsetReceivedTimestamp(request);
        Optional<Integer> offsetMessageId = parseOffsetMessageid(request);
        PageParams pageParams = parseEmailPageParams(request);
        var selectedEmailId = request.queryParams().first("selectedEmailId").map(Integer::parseInt);
        Optional<String> query = request.queryParams().first("query");
        if (query.isEmpty()) {
            throw new BadRequestException("Search URL must contain 'query' parameter");
        }
        SearchFolder searchFolder = new SearchFolder(new Query(query.get()));
        // TODO: implement search with paging
        Pagination pagination = new Pagination(offsetReceivedTimestamp, offsetMessageId, pageParams, PAGE_SIZE, Optional.empty(), false, false);
        renderAccount(response, accountId, searchFolder, new EmailPage(Collections.emptyList(), false, false, new PageParams(PageDirection.RIGHT, SortOrder.ASCENDING)), pagination, selectedEmailId, query);
    }

    private void renderAccount(ServerResponse response, int accountId, Folder folder, EmailPage emailPage, Pagination pagination, Optional<Integer> selectedEmailId, Optional<String> query) {
        List<EmailHeader> emailHeaders = emailPage.emails().stream().map(Email::header).toList();
        List<EmailGroup> emailGroups = query.isPresent() ? EmailGroup.createNoGroupEmailgroup(emailHeaders) : EmailGroup.createEmailGroups(emailHeaders);
        EmailGroupPage emailGroupPage = new EmailGroupPage(emailGroups, pagination);
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", accountService.getAccount(accountId));
        context.put("accounts", accountService.getAccounts());
        context.put("folders", folderService.getFolders(accountId));
        context.put("emailGroupPage", emailGroupPage);
        context.put("currentFolder", folder);
        if (selectedEmailId.isPresent()) {
            context.put("selectedEmailId", selectedEmailId.get());
        }
        if (query.isPresent()) {
            context.put("currentQuery", query.get());
        }
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, accountTemplate));
    }

    private void renderAccountWithoutFolders(ServerResponse response, int accountId) {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", accountService.getAccount(accountId));
        context.put("accounts", accountService.getAccounts());
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, accountWithoutFoldersTemplate));
    }

    private void emailCreationHandler(ServerRequest request, ServerResponse response) {
        // TODO: We create a new draft email and redirect to the composer
        boolean redirect = Boolean.parseBoolean(request.queryParams().first("redirect").orElse("true"));
        Optional<Integer> replyEmailId = request.queryParams().first("replyEmail").map(Integer::valueOf);
        Optional<Integer> forwardEmailId = request.queryParams().first("forwardEmail").map(Integer::valueOf);
        int newEmailId = replyEmailId.isPresent() ? 100 : forwardEmailId.isPresent() ? 200 : 42;
        // a client can post a request for a new email with or without a redirect. In the latter case we return the
        // composer location as the result and the client has to take care of going there himself
        if (redirect) {
            ResponseUtils.redirectAfterPost(response, getComposerLocation(newEmailId));
        } else {
            response.status(200);
            response.send(getComposerLocation(newEmailId).toString());
        }
    }

    private static URI getComposerLocation(int newEmailId) {
        return URI.create("/emails/%s/composer".formatted(newEmailId));
    }

    private NamedFolder findFolder(int folderId) {
        return folderService.getFolder(folderId);
    }

}
