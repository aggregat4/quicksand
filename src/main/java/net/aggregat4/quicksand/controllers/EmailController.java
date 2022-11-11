package net.aggregat4.quicksand.controllers;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class EmailController {

    private static final PebbleTemplate emailViewerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");

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

    @GetMapping(value = "/accounts/{accountId}/emails/{emailId}", produces = {"text/html"})
    public String emailViewerPage(@PathVariable int accountId, @PathVariable int emailId, @RequestParam(defaultValue = "false") boolean showImages) throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("accountId", accountId);
        context.put("showImages", showImages);
        if (emailId == 1) {
            context.put("email", MockEmailData.PLAINTEXT_EMAIL);
        } else {
            context.put("email", MockEmailData.HTML_EMAIL);
        }
        return PebbleRenderer.renderTemplate(context, emailViewerTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/emails/{emailId}/body", produces = {"text/html"})
    public String emailBodyPage(@PathVariable int accountId, @PathVariable int emailId, @RequestParam(defaultValue = "false") boolean showImages) throws IOException {
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
        return sw.toString();
    }

    @GetMapping(value = "/accounts/{accountId}/emails/{emailId}/attachments/{attachmentId}")
    public ResponseEntity<InputStreamSource> emailAttachment(@PathVariable int accountId, @PathVariable int emailId, @PathVariable int attachmentId, HttpServletResponse response) throws IOException {
        if (attachmentId == 1) {
            var attachment = MockEmailData.ATTACHMENT1;
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(attachment.mediaType());
            if (attachment.name() != null) {
                httpHeaders.setContentDisposition(ContentDisposition.attachment().filename(attachment.name()).build());
            }
            return new ResponseEntity<>(MockEmailData.sampleMp3Resource, httpHeaders, 200);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping(value = "/accounts/{accountId}/emails/selection")
    public void selectedEmailAction(
            @PathVariable int accountId,
            @RequestParam(name = "email_select") List<Integer> selectionIds,
            HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        var action = "undefined";
        for (Map.Entry<String, String[]> param : req.getParameterMap().entrySet()) {
            if (param.getKey().startsWith("email_selection_action_")) {
                action = param.getKey();
            }
        }
        System.out.printf("Selection action %s for emails %s%n", action, selectionIds.toString());
        // NOTE: it is unclear how reliable using referer is. It is very convenient and maybe for local applications
        // it is no problem
        String referer = req.getHeader("Referer");
        resp.sendRedirect(referer);
    }

    // TODO: on delete we may want to redirect to a more general URL since the email is gone?
    @PostMapping(value = "/accounts/{accountId}/emails/{emailId}/actions")
    public void emailAction(
            @PathVariable int accountId,
            @PathVariable int emailId,
            HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        var action = "undefined";
        for (Map.Entry<String, String[]> param : req.getParameterMap().entrySet()) {
            if (param.getKey().startsWith("action_")) {
                action = param.getKey();
            }
        }
        System.out.printf("Selection action %s for emails %s%n", action, emailId);
        // NOTE: it is unclear how reliable using referer is. It is very convenient and maybe for local applications
        // it is no problem
        String referer = req.getHeader("Referer");
        resp.sendRedirect(referer);
    }

}
