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
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
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
    private final DraftService draftService;
    private final AttachmentService attachmentService;
    private final OutboundMessageService outboundMessageService;

    public EmailWebService(EmailService emailService, DraftService draftService, AttachmentService attachmentService, OutboundMessageService outboundMessageService) {
        this.emailService = emailService;
        this.draftService = draftService;
        this.attachmentService = attachmentService;
        this.outboundMessageService = outboundMessageService;
    }

    @Override
    public void routing(HttpRules rules) {
        // NOTE: using a subresource to distinguish between a viewer and a composer view type. This should theoretically
        // be something like an accept header but I couldn't be bothered
        rules.get("/{emailId}/viewer", this::emailHandler);
        rules.get("/{emailId}/viewer/body", this::htmlEmailBodyHandler);
        rules.post("/selection", this::emailActionHandler);
        rules.get("/{emailId}/composer", this::emailComposerHandler);
        rules.post("/{emailId}/draft", this::draftSaveHandler);
        rules.post("/{emailId}/attachments", this::draftAttachmentUploadHandler);
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
        Optional<Draft> draft = draftService.getDraft(emailId);
        if (draft.isEmpty()) {
            response.status(404);
            response.send();
            return;
        }
        Map<String, Object> context = new HashMap<>();
        context.put("draft", draft.get());
        context.put("attachments", attachmentService.getDraftAttachments(emailId));
        Optional<String> validationErrors = request.query().first("validationErrors").map(s -> s);
        if (validationErrors.isPresent()) {
            context.put("validationErrors", validationErrors.get());
        }
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(context, emailComposerTemplate));
    }

    private void draftAttachmentUploadHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        if (draftService.getDraft(emailId).isEmpty()) {
            response.status(404);
            response.send();
            return;
        }

        MultiPart multiPart = request.content().as(MultiPart.GENERIC_TYPE);
        while (multiPart.hasNext()) {
            ReadablePart part = multiPart.next();
            if (!"uploaded-file".equals(part.name())) {
                part.consume();
                continue;
            }
            Optional<String> fileName = part.fileName().filter(name -> !name.isBlank());
            if (fileName.isEmpty()) {
                part.consume();
                continue;
            }
            try (var inputStream = part.inputStream()) {
                attachmentService.storeDraftAttachment(emailId, fileName.get(), part.contentType(), inputStream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ResponseUtils.redirectAfterPost(response, URI.create("/emails/%s/composer".formatted(emailId)));
    }

    private void draftSaveHandler(ServerRequest request, ServerResponse response) {
        int emailId = RequestUtils.intPathParam(request, "emailId");
        Map<String, String> formParams = parseFormEncoded(request.content().as(String.class));
        Optional<Draft> draft = draftService.saveDraft(
                emailId,
                formParams.getOrDefault("email-to", ""),
                formParams.getOrDefault("email-cc", ""),
                formParams.getOrDefault("email-bcc", ""),
                formParams.getOrDefault("email-subject", ""),
                formParams.getOrDefault("email-body", ""));
        if (draft.isEmpty()) {
            response.status(404);
            response.send();
            return;
        }
        response.headers().contentType(TEXT_HTML);
        response.send("<!DOCTYPE html><html><body data-save-status=\"ok\"></body></html>");
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
        if (shouldDeleteEmail) {
            if (!draftService.deleteDraft(emailId)) {
                response.status(404);
                response.send();
                return;
            }
            ResponseUtils.redirectAfterPost(response, URI.create("/emails/%s/queued?result=deleted".formatted(emailId)));
            return;
        }

        Optional<Draft> draft = draftService.saveDraft(
                emailId,
                valueOrEmpty(params.get("email-to")),
                valueOrEmpty(params.get("email-cc")),
                valueOrEmpty(params.get("email-bcc")),
                valueOrEmpty(params.get("email-subject")),
                valueOrEmpty(params.get("email-body")));
        if (draft.isEmpty()) {
            response.status(404);
            response.send();
            return;
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
            Optional<net.aggregat4.quicksand.domain.OutboundMessage> queuedMessage = outboundMessageService.queueDraftForDelivery(emailId);
            if (queuedMessage.isEmpty()) {
                response.status(404);
                response.send();
                return;
            }
            // queue email for sending and signal frontend that we were successfull
            ResponseUtils.redirectAfterPost(response, URI.create("/emails/%s/queued?result=queued".formatted(queuedMessage.get().id())));
        }
    }

    private void emailQueued(ServerRequest request, ServerResponse response) {
        // TODO: we should if-last modified here so we can instruct the browser to use the cached version as long we did not restart the program
        String notificationText = request.query().first("result")
                .map(result -> switch (result) {
                    case "deleted" -> "Draft was deleted.";
                    default -> "Email was queued for delivery.";
                })
                .orElse("Email was queued for delivery.");
        response.headers().contentType(TEXT_HTML);
        response.send(PebbleRenderer.renderTemplate(Map.of("notificationText", notificationText), emailQueuedTemplate));
    }

    private static void redirectWithValidationErrors(int emailId, String s, ServerResponse response) {
        String relativeUrl = "/emails/%s/composer?validationErrors=%s".formatted(emailId, URLEncoder.encode(s, StandardCharsets.UTF_8));
        URI composerUri = URI.create(relativeUrl);
        ResponseUtils.redirectAfterPost(response, composerUri);
    }

    private static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> params = new HashMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    private boolean isEmpty(String value) {
        return value == null || value.isBlank();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

}
