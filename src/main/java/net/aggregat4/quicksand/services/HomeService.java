package net.aggregat4.quicksand.services;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.MediaType;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.pebble.PebbleRenderer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeService implements Service {

    private static final PebbleTemplate homeTemplate = PebbleConfig.getEngine().getTemplate("templates/home.peb");

    @Override
    public void update(Routing.Rules rules) {
        rules.get("/", this::getHomeHandler);
    }

    private void getHomeHandler(ServerRequest request, ServerResponse response) {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "homepage");
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, homeTemplate));
    }

}
