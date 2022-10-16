package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import jakarta.servlet.http.HttpServletResponse;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public static final Actor EMAIL1_SENDER = new Actor("someone@somewhere.com", Optional.of("Someone"));
    public static final Actor EMAIL1_RECIPIENT = new Actor("me@example.com", Optional.of("Me Doe"));
    public static final String EMAIL1_SUBJECT = "Hey there how are you?";
    public static final ZonedDateTime EMAIL1_RECEIVEDDATE = ZonedDateTime.now();
    public static final Actor EMAIL2_SENDER = new Actor("foo@bar.net", Optional.empty());
    public static final Actor EMAIL2_RECIPIENT = new Actor("me@example.org", Optional.of("Doe, Me"));

    public static final String EMAIL2_SUBJECT = "Foo du fafa";
    public static final ZonedDateTime EMAIL2_RECEIVEDDATE = ZonedDateTime.now().minus(3, ChronoUnit.MINUTES);
    private static final Attachment ATTACHMENT1 = new Attachment(1, "sounds and music.mp3", 43534555, new org.springframework.http.MediaType("audio", "mpeg"));


    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}/emails/{emailId}", produces = {"text/html"})
    public String emailViewerPage(@PathVariable int accountId, @PathVariable int folderId, @PathVariable int emailId, @RequestParam(defaultValue = "false") boolean showImages) throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("accountId", accountId);
        context.put("folderId", folderId);
        context.put("showImages", showImages);
        if (emailId == 1) {
            context.put("email",
                    new Email(
                            new EmailHeader(
                                    1,
                                    EMAIL1_SENDER,
                                    EMAIL1_RECIPIENT,
                                    EMAIL1_SUBJECT,
                                    EMAIL1_RECEIVEDDATE,
                                    null,
                                    false,
                                    true,
                                    false),
                            true,
                            """
                                    Hi,
                                    
                                    This is an email body.
                                    
                                    -- 
                                    Sent from my iPhone
                                    """,
                            List.of(ATTACHMENT1)
                    )
            );
        } else {
            context.put("email",
                    new Email(
                            new EmailHeader(
                                    2,
                                    EMAIL2_SENDER,
                                    EMAIL2_RECIPIENT,
                                    EMAIL2_SUBJECT,
                                    EMAIL2_RECEIVEDDATE,
                                    null,
                                    false,
                                    false,
                                    false
                            ),
                            false,
                            null,
                            Collections.emptyList())
                    );
        }
        return PebbleRenderer.renderTemplate(context, emailViewerTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}/emails/{emailId}/body", produces = {"text/html"})
    public String emailBodyPage(@PathVariable int accountId, @PathVariable int folderId, @PathVariable int emailId, @RequestParam(defaultValue = "false") boolean showImages) throws IOException {
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
            HtmlSanitizer.sanitize(EMAIL2_BODY, IMAGES_POLICY.apply(renderer));
        } else {
            HtmlSanitizer.sanitize(EMAIL2_BODY, NO_IMAGES_POLICY.apply(renderer));
        }
        return sw.toString();
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}/emails/{emailId}/attachments/{attachmentId}")
    public ResponseEntity<InputStreamSource> emailAttachment(@PathVariable int accountId, @PathVariable int folderId, @PathVariable int emailId, @PathVariable int attachmentId, HttpServletResponse response) throws IOException {
        if (attachmentId == 1) {
            var attachment = ATTACHMENT1;
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(attachment.mediaType());
            if (attachment.name() != null) {
                httpHeaders.setContentDisposition(ContentDisposition.attachment().filename(attachment.name()).build());
            }
            return new ResponseEntity<>(new ClassPathResource("/sample-3s.mp3"), httpHeaders, 200);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    private static final String EMAIL2_BODY = """
<!doctype html>
<html>
  <head>
    <meta name="viewport" content="width=device-width" />
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <title>Simple Responsive HTML Email With Button</title>
  </head>
  <body class="">
    <script>alert("Script in an email!")</script>
    <table role="presentation" border="0" cellpadding="0" cellspacing="0" class="body">
      <tr>
        <td>&nbsp;</td>
        <td class="container">
          <div class="header">
            <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="100%">
              <tr>
                <td class="align-center" width="100%">
                  <a href="https://postdrop.io"><img src="https://cdn.postdrop.io/starter-templates-v0/postdrop-logo-dark.png" height="40" alt="Postdrop"></a>
                </td>
              </tr>
            </table>
          </div>
          <div class="content">

            <!-- START CENTERED WHITE CONTAINER -->
            <span class="preheader">This is preheader text. Some clients will show this text as a preview.</span>
            <table role="presentation" class="main">

              <!-- START MAIN CONTENT AREA -->
              <tr>
                <td class="wrapper">
                  <table role="presentation" border="0" cellpadding="0" cellspacing="0">
                    <tr>
                      <td>
                        <p>👋&nbsp; Welcome to Postdrop. A simple tool to help developers with HTML email.</p>
                        <p>✨&nbsp; HTML email templates are painful to build. So instead of spending hours or days trying to make your own, just use this template and call it a day.</p>
                        <p>⬇️&nbsp; Add your own content then download and copy over to your codebase or ESP. Postdrop will inline the CSS for you to make sure it doesn't fall apart when it lands in your inbox.</p>
                        <p>📬&nbsp; Postdrop also lets you send test emails to yourself. You just need to sign up first so we know you're not a spammer.</p>
                        <table role="presentation" border="0" cellpadding="0" cellspacing="0" class="btn btn-primary">
                          <tbody>
                            <tr>
                              <td align="center">
                                <table role="presentation" border="0" cellpadding="0" cellspacing="0">
                                  <tbody>
                                    <tr>
                                      <td> <a href="/signup" target="_blank">Sign Up For Postdrop</a> </td>
                                    </tr>
                                  </tbody>
                                </table>
                              </td>
                            </tr>
                          </tbody>
                        </table>
                        <p>💃&nbsp; That's it. Enjoy this free template.</p>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>

            <!-- END MAIN CONTENT AREA -->
            </table>

            <!-- START FOOTER -->
            <div class="footer">
              <table role="presentation" border="0" cellpadding="0" cellspacing="0">
                <tr>
                  <td class="content-block">
                    <span class="apple-link">Don't forget to add your address here</span>
                    <br> And <a href="https://postdrop.io">unsubscribe link</a> here.
                  </td>
                </tr>
                <tr>
                  <td class="content-block powered-by">
                    Powered by <a href="https://postdrop.io">Postdrop</a>.
                  </td>
                </tr>
              </table>
            </div>
            <!-- END FOOTER -->

          <!-- END CENTERED WHITE CONTAINER -->
          </div>
        </td>
        <td>&nbsp;</td>
      </tr>
    </table>
  </body>
</html>
""";

}
