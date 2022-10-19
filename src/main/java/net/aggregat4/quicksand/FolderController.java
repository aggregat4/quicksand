package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.Folder;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.Pagination;
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

import static net.aggregat4.quicksand.EmailController.*;

@RestController
public class FolderController {
    public static final int PAGE_SIZE = 100;
    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/folder.peb");

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
        List<NamedFolder> folders = List.of(new NamedFolder(1, "INBOX"), new NamedFolder(2, "Archive"), new NamedFolder(3, "Sent"), new NamedFolder(4, "Junk"));
        Folder currentFolder = folders.stream()
                .filter(f -> f.id() == folderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Folderid %s is unknown", folderId)));
        List<EmailHeader> emailHeaders = List.of(
                new EmailHeader(1, EMAIL1_SENDER, EMAIL1_RECIPIENT, EMAIL1_SUBJECT, EMAIL1_RECEIVEDDATE, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, false),
                new EmailHeader(2, EMAIL2_SENDER, EMAIL2_RECIPIENT, EMAIL2_SUBJECT, EMAIL2_RECEIVEDDATE, "Asd askl; sdkljhs ldkjfhaslkdjfhalksdjfh aslkd  falskjd alskdfhqw qwe ", false, false, false),
                new EmailHeader(3, new Actor("izzi342@gmail.com", Optional.of("Eddie Izzard")), EMAIL1_RECIPIENT, "This is the greatet thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), "Dear Mr. Michalson, This is the winter of my discontent. And I hope you are fine.", false, false, true),
                new EmailHeader(4, new Actor("ceo@ibm.com", Optional.of("John Hockenberry von Hockenstein")), EMAIL1_RECIPIENT, "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                new EmailHeader(5, new Actor("john@waterman.org", Optional.of("John Doe")), EMAIL1_RECIPIENT, "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true),
                new EmailHeader(6, new Actor("whatevs@mail.org", Optional.of("Evan Watts")), EMAIL1_RECIPIENT, "Dude, wassup!", ZonedDateTime.now().minus(7, ChronoUnit.DAYS), "And now my dear there is a chance that we may meet again in fields", false, false, true)
        );

        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "folderpage");
        context.put("currentAccount", new Account(accountId, "foo@example.com"));
        context.put("currentFolder", currentFolder);
        context.put("folders", folders);
        context.put("pagination", new Pagination(from, Math.min(from + PAGE_SIZE, total), total, PAGE_SIZE));
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));
        context.put("emailHeaders", emailHeaders);
        return PebbleRenderer.renderTemplate(context, accountTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/search", produces = {"text/html"})
    public String folderPage(@PathVariable int accountId, @RequestParam(required = true) String query) throws IOException {
        return null;
    }

}
