package net.aggregat4.quicksand.webservice;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.MediaType;
import io.helidon.webserver.BadRequestException;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.EmailGroup;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.Folder;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.Query;
import net.aggregat4.quicksand.domain.SearchFolder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.FolderService;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AccountWebService implements Service {
    public static final int PAGE_SIZE = 100;

    private static final PebbleTemplate folderTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");
    private FolderService folderService;
    private AccountService accountService;

    public AccountWebService(FolderService folderService, AccountService accountService) {
        this.folderService = folderService;
        this.accountService = accountService;
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
        Optional<Folder> firstFolder = Optional.empty();
        if (! folders.isEmpty()) {
            firstFolder = Optional.of(folders.get(0));
        }
        renderAccount(request, response, accountId, firstFolder, 1, Optional.empty(), Optional.empty());
    }

    private void getFolderHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        int folderId = RequestUtils.intPathParam(request, "folderId");
        int from = request.queryParams().first("from").map(Integer::parseInt).orElse(1);
        var selectedEmailId = request.queryParams().first("selectedEmailId").map(Integer::parseInt);
        Folder folder = findFolder(folderId);
        renderAccount(request, response, accountId, Optional.of(folder), from, selectedEmailId, Optional.empty());
    }

    private void getSearchHandler(ServerRequest request, ServerResponse response) {
        int accountId = RequestUtils.intPathParam(request, "accountId");
        int from = request.queryParams().first("from").map(Integer::parseInt).orElse(1);
        var selectedEmailId = request.queryParams().first("selectedEmailId").map(Integer::parseInt);
        Optional<String> query = request.queryParams().first("query");
        if (query.isEmpty()) {
            throw new BadRequestException("Search URL must contain 'query' parameter");
        }
        SearchFolder searchFolder = new SearchFolder(new Query(query.get()));
        renderAccount(request, response, accountId, Optional.of(searchFolder), from, selectedEmailId, query);
    }

    private void renderAccount(ServerRequest request, ServerResponse response, int accountId, Optional<Folder> folder, int from, Optional<Integer> selectedEmailId, Optional<String> query) {
        int total = 2526;
        if (from > total || from < 1) {
            throw new IllegalArgumentException("Accounts page called with invalid pagination offset");
        }
        List<EmailGroup> emailGroups = getMockEmailGroups(query.isPresent());
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("accounts", accountService.getAccounts());
        context.put("currentAccount", accountService.getAccount(accountId));
        if (folder.isPresent()) {
            context.put("currentFolder", folder.get());
        }
        context.put("folders", folderService.getFolders(accountId));
        if (query.isPresent()) {
            context.put("pagination", new Pagination(from, from + PAGE_SIZE, Optional.empty(), PAGE_SIZE));
        } else {
            context.put("pagination", new Pagination(from, Math.min(from + PAGE_SIZE, total), Optional.of(total), PAGE_SIZE));
        }
        context.put("emailGroups", emailGroups);
        context.put("currentQuery", query);
        if (selectedEmailId.isPresent()) {
            context.put("selectedEmailId", selectedEmailId.get());
        }
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, folderTemplate));
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

    private static List<EmailGroup> getMockEmailGroups(boolean returnSearchResults) {
        if (returnSearchResults) {
            // The search results are "just" "normal" paginated results but there are some special things
            // - there is probably no count to the search (too expensive)
            // - the following strings/texts can have highlighted snippets: sender name, sender email, subject, bodyExcerpt
            // - we can assume that those strings were escaped and then highlighted, so they can be displayed raw
            return List.of(
                    EmailGroup.createNoGroupEmailgroup(List.of(
                            new EmailHeader(1, 1, List.of(MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT), MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE,MockEmailData.EMAIL1_RECEIVEDDATE.toEpochSecond(),  MockEmailData.EMAIL1_SENTDATE, MockEmailData.EMAIL1_SENTDATE.toEpochSecond(), "Hey you, this is me <mark>John</mark> sending you this somewhere what is this and this snippet just goes on", false, true, false),
                            new EmailHeader(3, 3, List.of(new Actor(ActorType.SENDER, "izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT), "This is the greatest <mark>john</mark>-like thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), 0, ZonedDateTime.now().minus(17, ChronoUnit.MINUTES), 0, "Dear Mr. Michalson, Mr. <mark>John</mark> This is the winter of my discontent. And I hope you are fine.", false, false, true),
                            new EmailHeader(4, 4, List.of(new Actor(ActorType.SENDER, "ceo@ibm.com", Optional.of("<mark>John</mark> Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT), "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                            new EmailHeader(5, 5, List.of(new Actor(ActorType.SENDER, "<mark>john</mark>@waterman.org", Optional.of("<mark>John</mark> Doe")), MockEmailData.EMAIL1_RECIPIENT), "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true)
                    ))
            );
        } else {
            return List.of(
                    new EmailGroup.TodayEmailGroup(List.of(
                            new EmailHeader(1, 1, List.of(MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT), MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE, 0, MockEmailData.EMAIL1_SENTDATE, 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, false),
                            new EmailHeader(2, 2, List.of(MockEmailData.EMAIL2_SENDER, MockEmailData.EMAIL2_RECIPIENT), MockEmailData.EMAIL2_SUBJECT, MockEmailData.EMAIL2_RECEIVEDDATE, 0, MockEmailData.EMAIL2_SENTDATE, 0, "Asd askl; sdkljhs ldkjfhaslkdjfhalksdjfh aslkd  falskjd alskdfhqw qwe ", false, false, false))),
                    new EmailGroup.ThisWeekEmailGroup(List.of(
                            new EmailHeader(3, 3, List.of(new Actor(ActorType.SENDER, "izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT), "This is the greatest thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), 0, ZonedDateTime.now().minus(47, ChronoUnit.MINUTES), 0, "Dear Mr. Michalson, This is the winter of my discontent. And I hope you are fine.", false, false, true))),
                    new EmailGroup.ThisMonthEmailGroup(List.of(
                            new EmailHeader(4, 4, List.of(new Actor(ActorType.SENDER, "ceo@ibm.com", Optional.of("John Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT), "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                            new EmailHeader(5, 5, List.of(new Actor(ActorType.SENDER, "john@waterman.org", Optional.of("John Doe")), MockEmailData.EMAIL1_RECIPIENT), "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true),
                            new EmailHeader(6, 6, List.of(new Actor(ActorType.SENDER, "whatevs@mail.org", Optional.of("Evan Watts")), MockEmailData.EMAIL1_RECIPIENT), "Dude, wassup!", ZonedDateTime.now().minus(7, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(7, ChronoUnit.DAYS), 0, "And now my dear there is a chance that we may meet again in fields", false, false, true)))
            );
        }
    }


}
