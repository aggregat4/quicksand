package net.aggregat4.quicksand.webservice;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.MediaType;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountService;

import java.util.HashMap;
import java.util.Map;

public class HomeWebService implements Service {

    private static final PebbleTemplate homeTemplate = PebbleConfig.getEngine().getTemplate("templates/home.peb");

    private final AccountService accountService;

    public HomeWebService(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public void update(Routing.Rules rules) {
        rules.get("/", this::getHomeHandler);
    }

    private void getHomeHandler(ServerRequest request, ServerResponse response) {
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "homepage");
        context.put("accounts", accountService.getAccounts());
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, homeTemplate));
    }

}
