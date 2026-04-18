package net.aggregat4.quicksand.webservice;

import io.helidon.http.HttpMediaType;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.HashMap;
import java.util.Map;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AccountService;

public class HomeWebService implements HttpService {
  private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");

  private static final PebbleTemplate homeTemplate =
      PebbleConfig.getEngine().getTemplate("templates/home.peb");

  private final AccountService accountService;

  public HomeWebService(AccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public void routing(HttpRules rules) {
    rules.get("/", this::getHomeHandler);
  }

  private void getHomeHandler(ServerRequest request, ServerResponse response) {
    Map<String, Object> context = new HashMap<>();
    context.put("bodyclass", "homepage");
    context.put("accounts", accountService.getAccounts());
    response.headers().contentType(TEXT_HTML);
    response.send(PebbleRenderer.renderTemplate(context, homeTemplate));
  }
}
