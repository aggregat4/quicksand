package net.aggregat4.quicksand.services;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.helidon.common.http.FormParams;
import io.helidon.common.http.MediaType;
import io.helidon.media.multipart.ReadableBodyPart;
import io.helidon.webserver.BadRequestException;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EmailService implements Service {

    private static final PebbleTemplate emailViewerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");


    private static final PebbleTemplate emailComposerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailcomposer.peb");

    private static final PebbleTemplate emailQueuedTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailqueued.peb");

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
        rules.get("/{emailId}/queued", this::emailQueued);
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
        Email email = loadEmail(emailId);
        Map<String, Object> context = new HashMap<>();
        context.put("email", email);
        Optional<String> validationErrors = request.queryParams().first("validationErrors");
        if (validationErrors.isPresent()) {
            context.put("validationErrors", validationErrors.get());
        }
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, emailComposerTemplate));
    }

    private Email loadEmail(int emailId) {
        return switch(emailId) {
            case 100 -> MockEmailData.REPLY_EMAIL;
            case 200 -> MockEmailData.FORWARD_EMAIL;
            default -> MockEmailData.NEW_EMAIL;
        };
    }

    /**
     * This can be a delete or a send, we validate that these actions are selected.
     */
    private void emailSendOrDeleteHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        Map<String, String> params = new HashMap<>();
        request.content().asStream(ReadableBodyPart.class).forEach(part -> {
            if (part.name().equals("uploaded-file")) {
                // TODO: save actual files somewhere and track that list here
                params.put("uploaded-file", params.get("uploaded-file") + ", " + part.filename());
                // When not consuming parts, we need to drain them
                part.drain();
            } else {
                part.content().as(String.class).thenAccept(s -> {
                    params.put(part.name(), s);
                });
            }
        }).onError(throwable -> {
            // error handling
        }).onComplete(() -> {
            // send response
            var shouldSendEmail = params.containsKey("email-action-send");
            var shouldDeleteEmail = params.containsKey("email-action-delete");
            if ((!shouldDeleteEmail && !shouldSendEmail) || (shouldDeleteEmail && shouldSendEmail)) {
                throw new BadRequestException("Posting to a concrete email requires either the delete or send action");
            }
            System.out.printf("Action '%s' for email %s%n", shouldDeleteEmail ? "delete" : "send", emailId);
            // validate
            List<String> validationErrors = new ArrayList<>();
            if (isEmpty(params.get("email-to"))) {
                validationErrors.add("Missing 'To' field");
            }
            if (isEmpty(params.get("email-subject")) && isEmpty(params.get("email-body"))) {
                validationErrors.add("Require at least a 'Subject' or a 'Body'");
            }
            System.out.println("The following files were uploaded: " + params.get("uploaded-file"));
            if (! validationErrors.isEmpty()) {
                redirectWithValidationErrors(emailId, String.join(" ", validationErrors), response);
            } else {
                // queue email for sending and signal frontend that we were successfull
                ResponseUtils.redirectAfterPost(response, URI.create("/emails/%s/queued".formatted(emailId)));
            }
        }).ignoreElement();
    }

    private void emailQueued(ServerRequest request, ServerResponse response) {
        // TODO: we should if-last modified here so we can instruct the browser to use the cached version as long we did not restart the program
        response.headers().contentType(MediaType.TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(Collections.emptyMap(), emailQueuedTemplate));
    }

    private static void redirectWithValidationErrors(int emailId, String s, ServerResponse response) {
        String relativeUrl = "/emails/%s/composer?validationErrors=%s".formatted(emailId, URLEncoder.encode(s, StandardCharsets.UTF_8));
        URI composerUri = URI.create(relativeUrl);
        ResponseUtils.redirectAfterPost(response, composerUri);
    }

    private boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().equals("");
    }

}
