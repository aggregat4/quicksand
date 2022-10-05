package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HomeController {
    private static final PebbleTemplate homeTemplate =
            PebbleConfig.getEngine().getTemplate("templates/home.peb");

    @GetMapping(value = "/", produces = {"text/html"})
    public String homePage() throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "homepage");
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));
        return PebbleRenderer.renderTemplate(context, homeTemplate);
    }

}
