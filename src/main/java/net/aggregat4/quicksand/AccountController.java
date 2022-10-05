package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AccountController {
    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");

    @GetMapping(value = "/account/{id}", produces = {"text/html"})
    public String accountPage(@PathVariable int id) throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", "foo@example.com");
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));
        return PebbleRenderer.renderTemplate(context, accountTemplate);
    }

}
