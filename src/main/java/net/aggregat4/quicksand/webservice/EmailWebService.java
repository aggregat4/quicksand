package net.aggregat4.quicksand.webservice;

import io.helidon.http.BadRequestException;
import io.helidon.http.HttpMediaType;
import io.helidon.http.media.multipart.MultiPart;
import io.helidon.http.media.multipart.ReadablePart;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.EmailService;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public class EmailWebService implements HttpService {
    private static final HttpMediaType TEXT_HTML = HttpMediaType.create("text/html; charset=UTF-8");
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailWebService.class);

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
    private final EmailService emailService;

    public EmailWebService(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public void routing(HttpRules rules) {
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
        Optional<Email> email = emailService.getMessage(emailId);
        if (email.isEmpty()) {
            response.status(404);
            response.send();
            return;
        }
        Map<String, Object> context = new HashMap<>();
        context.put("showImages", showImages);
        context.put("email", email.get());
        // TODO: we should if-last modified here so we can instruct the browser to use the cached version as long we did not restart the program
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, emailViewerTemplate));
    }

    private void htmlEmailBodyHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        boolean showImages = getShowImagesParam(request);
        Optional<Email> email = emailService.getMessage(emailId);
        if (email.isEmpty() || email.get().plainText() || email.get().body() == null) {
            response.status(404);
            response.send();
            return;
        }
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
            HtmlSanitizer.sanitize(email.get().body(), IMAGES_POLICY.apply(renderer));
        } else {
            HtmlSanitizer.sanitize(email.get().body(), NO_IMAGES_POLICY.apply(renderer));
        }
        response.headers().contentType(TEXT_HTML);
        ResponseUtils.setCacheControlImmutable(response);
        response.send(sw.toString());
    }

    private static boolean getShowImagesParam(ServerRequest request) {
        return Boolean.parseBoolean(request.query().first("showImages").orElse("false"));
    }

    private void emailActionHandler(ServerRequest request, ServerResponse response) {
        Map<String, List<String>> formParams = request.content().as(new io.helidon.common.GenericType<Map<String, List<String>>>() {});
        var action = "undefined";
        for (Map.Entry<String, List<String>> param : formParams.entrySet()) {
            if (param.getKey().startsWith("email_action_")) {
                action = param.getKey();
            }
        }
        List<String> selectionIds = formParams.getOrDefault("email_select", Collections.emptyList());
        LOGGER.info("Selection action {} for emails {}", action, selectionIds);
        // NOTE: it is unclear how reliable using referer is. It is very convenient and maybe for local applications
        // it is no problem
        URI location = request.headers().referer().orElse(URI.create("/"));
        ResponseUtils.redirectAfterPost(response, location);
    }

    private void emailComposerHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        Email email = loadEmail(emailId);
        Map<String, Object> context = new HashMap<>();
        context.put("email", email);
        Optional<String> validationErrors = request.query().first("validationErrors").map(s -> s);
        if (validationErrors.isPresent()) {
            context.put("validationErrors", validationErrors.get());
        }
        response.headers().contentType(TEXT_HTML);
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
        MultiPart multiPart = request.content().as(MultiPart.GENERIC_TYPE);
        while (multiPart.hasNext()) {
            ReadablePart part = multiPart.next();
            if (part.name().equals("uploaded-file")) {
                // TODO: save actual files somewhere and track that list here
                params.merge(
                        "uploaded-file",
                        part.fileName().orElse(""),
                        (current, next) -> current == null || current.isBlank() ? next : current + ", " + next);
                part.consume();
            } else {
                params.put(part.name(), part.as(String.class));
            }
        }

        var shouldSendEmail = params.containsKey("email-action-send");
        var shouldDeleteEmail = params.containsKey("email-action-delete");
        if ((!shouldDeleteEmail && !shouldSendEmail) || (shouldDeleteEmail && shouldSendEmail)) {
            throw new BadRequestException("Posting to a concrete email requires either the delete or send action");
        }
        LOGGER.info("Action '{}' for email {}", shouldDeleteEmail ? "delete" : "send", emailId);
        // validate
        List<String> validationErrors = new ArrayList<>();
        if (isEmpty(params.get("email-to"))) {
            validationErrors.add("Missing 'To' field");
        }
        if (isEmpty(params.get("email-subject")) && isEmpty(params.get("email-body"))) {
            validationErrors.add("Require at least a 'Subject' or a 'Body'");
        }
        LOGGER.debug("Uploaded files: {}", params.get("uploaded-file"));
        if (!validationErrors.isEmpty()) {
            redirectWithValidationErrors(emailId, String.join(" ", validationErrors), response);
        } else {
            // queue email for sending and signal frontend that we were successfull
            ResponseUtils.redirectAfterPost(response, URI.create("/emails/%s/queued".formatted(emailId)));
        }
    }

    private void emailQueued(ServerRequest request, ServerResponse response) {
        // TODO: we should if-last modified here so we can instruct the browser to use the cached version as long we did not restart the program
        response.headers().contentType(TEXT_HTML);
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
        return value == null || value.isBlank();
    }

}
