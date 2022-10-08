package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AccountController {
    public static final int PAGE_SIZE = 100;
    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");

    @GetMapping(value = "/accounts/{id}", produces = {"text/html"})
    public String accountPage(@PathVariable int id, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        return accountFolderPage(id, 1, 1);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}", produces = {"text/html"})
    public String accountFolderPage(@PathVariable int accountId, @PathVariable int folderId, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        int total = 2526;
        if (from > total || from < 1) {
            throw new IllegalArgumentException("Accounts page called with invalid pagination offset");
        }
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", new Account(accountId, "foo@example.com"));
        List<Folder> folders = List.of(new Folder(1, "INBOX"), new Folder(2, "Archive"), new Folder(3, "Sent"), new Folder(4, "Junk"));
        Folder currentFolder = folders.stream()
                .filter(f -> f.id() == folderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Folderid %s is unknown", folderId)));
        context.put("currentFolder", currentFolder);
        context.put("folders", folders);
        context.put("pagination", new Pagination(from, Math.min(from + PAGE_SIZE, total), total, PAGE_SIZE));
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));
        return PebbleRenderer.renderTemplate(context, accountTemplate);
    }

}
