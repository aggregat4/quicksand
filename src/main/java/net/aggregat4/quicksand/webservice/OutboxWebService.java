package net.aggregat4.quicksand.webservice;

import io.helidon.http.HttpMediaType;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.OutboundMessageService;

import java.util.Map;

public class OutboxWebService implements HttpService {
    private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");
    private static final PebbleTemplate emailViewerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");

    private final OutboundMessageService outboundMessageService;

    public OutboxWebService(OutboundMessageService outboundMessageService) {
        this.outboundMessageService = outboundMessageService;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/{outboundMessageId}/viewer", this::queuedMessageViewerHandler);
    }

    private void queuedMessageViewerHandler(ServerRequest request, ServerResponse response) {
        int outboundMessageId = RequestUtils.intPathParam(request, "outboundMessageId");
        var email = outboundMessageService.getQueuedMessage(outboundMessageId);
        if (email.isEmpty()) {
            response.status(404);
            response.send();
            return;
        }
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(Map.of(
                "email", email.get(),
                "showImages", false,
                "readOnly", true), emailViewerTemplate));
    }
}
