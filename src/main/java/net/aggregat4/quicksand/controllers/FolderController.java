package net.aggregat4.quicksand.controllers;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.EmailGroup;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.Folder;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.Pagination;
import net.aggregat4.quicksand.domain.Query;
import net.aggregat4.quicksand.domain.SearchFolder;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class FolderController {
    public static final int PAGE_SIZE = 100;
    private static final PebbleTemplate folderTemplate =
            PebbleConfig.getEngine().getTemplate("templates/folder.peb");
    private final List<NamedFolder> NAMED_FOLDERS = List.of(new NamedFolder(1, "INBOX"), new NamedFolder(2, "Archive"), new NamedFolder(3, "Sent"), new NamedFolder(4, "Junk"));
    private final List<Account> ACCOUNTS = List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org"));

    @GetMapping(value = "/accounts/{id}", produces = {"text/html"})
    public String accountPage(@PathVariable int id, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        return folderPage(id, 1, 1);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}", produces = {"text/html"})
    public String folderPage(@PathVariable int accountId, @PathVariable int folderId, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        int total = 2526;
        if (from > total || from < 1) {
            throw new IllegalArgumentException("Accounts page called with invalid pagination offset");
        }
        Folder currentFolder = NAMED_FOLDERS.stream()
                .filter(f -> f.id() == folderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Folderid %s is unknown", folderId)));
        List<EmailGroup> emailGroups = List.of(
                new EmailGroup.TodayEmailGroup(List.of(
                    new EmailHeader(1, MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT, MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, false),
                    new EmailHeader(2, MockEmailData.EMAIL2_SENDER, MockEmailData.EMAIL2_RECIPIENT, MockEmailData.EMAIL2_SUBJECT, MockEmailData.EMAIL2_RECEIVEDDATE, "Asd askl; sdkljhs ldkjfhaslkdjfhalksdjfh aslkd  falskjd alskdfhqw qwe ", false, false, false))),
                new EmailGroup.ThisWeekEmailGroup(List.of(
                    new EmailHeader(3, new Actor("izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT, "This is the greatest thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), "Dear Mr. Michalson, This is the winter of my discontent. And I hope you are fine.", false, false, true))),
                new EmailGroup.ThisMonthEmailGroup(List.of(
                    new EmailHeader(4, new Actor("ceo@ibm.com", Optional.of("John Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT, "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                    new EmailHeader(5, new Actor("john@waterman.org", Optional.of("John Doe")), MockEmailData.EMAIL1_RECIPIENT, "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true),
                    new EmailHeader(6, new Actor("whatevs@mail.org", Optional.of("Evan Watts")), MockEmailData.EMAIL1_RECIPIENT, "Dude, wassup!", ZonedDateTime.now().minus(7, ChronoUnit.DAYS), "And now my dear there is a chance that we may meet again in fields", false, false, true)))
        );

        Map<String, Object> context = new HashMap<>();
        context.put("accounts", ACCOUNTS);
        context.put("bodyclass", "folderpage");
        context.put("currentAccount", new Account(accountId, "foo@example.com"));
        context.put("currentFolder", currentFolder);
        context.put("folders", NAMED_FOLDERS);
        context.put("pagination", new Pagination(from, Math.min(from + PAGE_SIZE, total), Optional.of(total), PAGE_SIZE));
        context.put("emailGroups", emailGroups);
        return PebbleRenderer.renderTemplate(context, folderTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/search", produces = {"text/html"})
    public String folderPage(@PathVariable int accountId, @RequestParam(required = false, defaultValue = "1") Integer from, @RequestParam(required = true) String query) throws IOException {
        SearchFolder searchFolder = new SearchFolder(new Query(query));
        // The search results are "just" "normal" paginated results but there are some special things
        // - there is probably no count to the search (too expensive)
        // - the following strings/texts can have highlighted snippets: sender name, sender email, subject, bodyExcerpt
        // - we can assume that those strings were escaped and then highlighted so they can be displayed raw
        List<EmailHeader> searchResults = List.of(
                new EmailHeader(1, MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT, MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE, "Hey you, this is me <mark>John</mark> sending you this somewhere what is this and this snippet just goes on", false, true, false),
                new EmailHeader(3, new Actor("izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT, "This is the greatest <mark>john</mark>-like thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), "Dear Mr. Michalson, Mr. <mark>John</mark> This is the winter of my discontent. And I hope you are fine.", false, false, true),
                new EmailHeader(4, new Actor("ceo@ibm.com", Optional.of("<mark>John</mark> Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT, "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                new EmailHeader(5, new Actor("<mark>john</mark>@waterman.org", Optional.of("<mark>John</mark> Doe")), MockEmailData.EMAIL1_RECIPIENT, "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true)
        );

        Map<String, Object> context = new HashMap<>();
        context.put("accounts", ACCOUNTS);
        context.put("bodyclass", "folderpage");
        context.put("currentAccount", new Account(accountId, "foo@example.com"));
        context.put("currentSearchFolder", searchFolder);
        context.put("folders", NAMED_FOLDERS);
        context.put("pagination", new Pagination(from, from + PAGE_SIZE, Optional.empty(), PAGE_SIZE));
        context.put("emailHeaders", searchResults);
        context.put("currentQuery", query);
        return PebbleRenderer.renderTemplate(context, folderTemplate);
    }

}
