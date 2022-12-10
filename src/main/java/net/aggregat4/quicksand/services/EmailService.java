package net.aggregat4.quicksand.services;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.FormParams;
import io.helidon.common.http.MediaType;
import io.helidon.webserver.BadRequestException;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EmailService implements Service {

    private static final PebbleTemplate emailViewerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");


    private static final PebbleTemplate emailComposerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailcomposer.peb");

    private static final PolicyFactory NO_IMAGES_POLICY = new HtmlPolicyBuilder()
            .allowCommonBlockElements()
            .allowElements("table", "tr", "td", "a", "img")
            .allowStyling()
            .allowCommonInlineFormattingElements()
            .toFactory();

    private static final PolicyFactory IMAGES_POLICY = new HtmlPolicyBuilder()
            .allowCommonBlockElements()
            .allowCommonInlineFormattingElements()
            .allowElements("table", "tr", "td", "a", "img")
            .allowStyling()
            // TODO: verify whether this is enough to just enable images or whether it allows too much
            .allowStandardUrlProtocols()
            .allowAttributes("src", "href").globally()
            .toFactory();

    @Override
    public void update(Routing.Rules rules) {
        // NOTE: using a subresource to distinguish between a viewer and a composer view type. This should theoretically
        // be something like an accept header but I couldn't be bothered
        rules.get("/{emailId}/viewer", this::emailHandler);
        rules.get("/{emailId}/viewer/body", this::htmlEmailBodyHandler);
        rules.post("/selection", this::emailActionHandler);
        rules.get("/{emailId}/composer", this::emailComposerHandler);
        rules.post("/{emailId}", this::emailSendOrDeleteHandler);
    }

    private void emailHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        boolean showImages = getShowImagesParam(request);
        Map<String, Object> context = new HashMap<>();
        context.put("showImages", showImages);
        if (emailId == 1) {
            context.put("email", MockEmailData.PLAINTEXT_EMAIL);
        } else {
            context.put("email", MockEmailData.HTML_EMAIL);
        }
        // TODO: we should if-last modified here so we can instruct the browser to use the cached version as long we did not restart the program
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, emailViewerTemplate));
    }

    private void htmlEmailBodyHandler(ServerRequest request, ServerResponse response) {
        boolean showImages = getShowImagesParam(request);
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        HtmlStreamRenderer renderer = HtmlStreamRenderer.create(
                bw,
                // Receives notifications on a failure to write to the output.
                ex -> {
                    // System.out suppresses IOExceptions
                    throw new AssertionError(null, ex);
                },
                // Our HTML parser is very lenient, but this receives notifications on
                // truly bizarre inputs.
                x -> {
                    throw new AssertionError(x);
                });
        if (showImages) {
            HtmlSanitizer.sanitize(MockEmailData.HTML_EMAIL_BODY, IMAGES_POLICY.apply(renderer));
        } else {
            HtmlSanitizer.sanitize(MockEmailData.HTML_EMAIL_BODY, NO_IMAGES_POLICY.apply(renderer));
        }
        response.headers().contentType(MediaType.TEXT_HTML);
        ResponseUtils.setCacheControlImmutable(response);
        response.send(sw.toString());
    }

    private static boolean getShowImagesParam(ServerRequest request) {
        return Boolean.parseBoolean(request.queryParams().computeSingleIfAbsent("showImages", (key) -> "false").iterator().next());
    }

    private void emailActionHandler(ServerRequest request, ServerResponse response) {
        request.content().as(FormParams.class).thenAccept(fp -> {
            var action = "undefined";
            for (Map.Entry<String, List<String>> param : fp.toMap().entrySet()) {
                if (param.getKey().startsWith("email_action_")) {
                    action = param.getKey();
                }
            }
            List<String> selectionIds = fp.all("email_select");
            System.out.printf("Selection action %s for emails %s%n", action, selectionIds.toString());
            // NOTE: it is unclear how reliable using referer is. It is very convenient and maybe for local applications
            // it is no problem
            URI location = request.headers().referer().orElse(URI.create("/"));
            ResponseUtils.redirectAfterPost(response, location);
        });
    }

    private void emailComposerHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        Map<String, Object> context = new HashMap<>();
        context.put("emailId", emailId);
        Optional<String> validationErrors = request.queryParams().first("validationErrors");
        if (validationErrors.isPresent()) {
            context.put("validationErrors", validationErrors.get());
        }
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, emailComposerTemplate));
    }

    /**
     * This can be a delete or a send, we validate that these actions are selected.
     */
    private void emailSendOrDeleteHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        request.content().as(FormParams.class).thenAccept(fp -> {
            Map<String, List<String>> params = fp.toMap();
            var shouldSendEmail = params.containsKey("email-action-send");
            var shouldDeleteEmail = params.containsKey("email-action-delete");
            if ((!shouldDeleteEmail && !shouldSendEmail) || (shouldDeleteEmail && shouldSendEmail)) {
                throw new BadRequestException("Posting to a concrete email requires either the delete or send action");
            }
            System.out.printf("Action '%s' for email %s%n", shouldDeleteEmail ? "delete" : "send", emailId);
            // validate
            if (!params.containsKey("email-to") || params.get("email-to").isEmpty()) {
                // TODO: redirect to the composer again but transmit validation errors in the URL?
                String relativeUrl = "/emails/%s/composer?validationErrors=%s".formatted(emailId, URLEncoder.encode("Missing to field", StandardCharsets.UTF_8));
                URI composerUri = URI.create(relativeUrl);
                ResponseUtils.redirectAfterPost(response, composerUri);
            }
        }).exceptionallyAccept(ResponseUtils.asyncExceptionConsumer(response));
    }

}
