package net.aggregat4.quicksand;

import com.mitchellbosecke.pebble.template.PebbleTemplate;
import net.aggregat4.quicksand.configuration.PebbleConfig;
import net.aggregat4.quicksand.pebble.PebbleRenderer;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class AccountController {
    public static final int PAGE_SIZE = 100;
    private static final PebbleTemplate accountTemplate =
            PebbleConfig.getEngine().getTemplate("templates/account.peb");

    private static final PebbleTemplate emailViewerTemplate =
            PebbleConfig.getEngine().getTemplate("templates/emailviewer.peb");


    public static final Actor EMAIL1_SENDER = new Actor("someone@somewhere.com", Optional.of("Someone"));
    public static final String EMAIL1_SUBJECT = "Hey there how are you?";
    public static final ZonedDateTime EMAIL1_RECEIVEDDATE = ZonedDateTime.now();
    public static final Actor EMAIL2_SENDER = new Actor("foo@bar.net", Optional.empty());
    public static final String EMAIL2_SUBJECT = "Foo du fafa";
    public static final ZonedDateTime EMAIL2_RECEIVEDDATE = ZonedDateTime.now().minus(3, ChronoUnit.MINUTES);


    @GetMapping(value = "/accounts/{id}", produces = {"text/html"})
    public String accountPage(@PathVariable int id, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        return accountFolderPage(id, 1, 1);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}", produces = {"text/html"})
    public String accountFolderPage(@PathVariable int accountId, @PathVariable int folderId, @RequestParam(required = false, defaultValue = "1") Integer from) throws IOException {
        int total = 2526;
        if (from > total || from < 1) {
            throw new IllegalArgumentException("Accounts page called with invalid pagination offset");
        }
        Map<String, Object> context = new HashMap<>();
        context.put("bodyclass", "accountpage");
        context.put("currentAccount", new Account(accountId, "foo@example.com"));
        List<Folder> folders = List.of(new Folder(1, "INBOX"), new Folder(2, "Archive"), new Folder(3, "Sent"), new Folder(4, "Junk"));
        Folder currentFolder = folders.stream()
                .filter(f -> f.id() == folderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Folderid %s is unknown", folderId)));
        context.put("currentFolder", currentFolder);
        context.put("folders", folders);
        context.put("pagination", new Pagination(from, Math.min(from + PAGE_SIZE, total), total, PAGE_SIZE));
        context.put("accounts", List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org")));

        List<EmailHeader> emailHeaders = List.of(
                new EmailHeader(1, EMAIL1_SENDER, EMAIL1_SUBJECT, EMAIL1_RECEIVEDDATE, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, false),
                new EmailHeader(2, EMAIL2_SENDER, EMAIL2_SUBJECT, EMAIL2_RECEIVEDDATE, "Asd askl; sdkljhs ldkjfhaslkdjfhalksdjfh aslkd  falskjd alskdfhqw qwe ", false, false, false),
                new EmailHeader(3, new Actor("izzi342@gmail.com", Optional.of("Eddie Izzard")), "This is the greatet thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), "Dear Mr. Michalson, This is the winter of my discontent. And I hope you are fine.", false, false, true),
                new EmailHeader(4, new Actor("ceo@ibm.com", Optional.of("John Hockenberry von Hockenstein")), "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
                new EmailHeader(5, new Actor("john@waterman.org", Optional.of("John Doe")), "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true),
                new EmailHeader(6, new Actor("whatevs@mail.org", Optional.of("Evan Watts")), "Dude, wassup!", ZonedDateTime.now().minus(7, ChronoUnit.DAYS), "And now my dear there is a chance that we may meet again in fields", false, false, true)
        );
        context.put("emailHeaders", emailHeaders);
        return PebbleRenderer.renderTemplate(context, accountTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}/emails/{emailId}", produces = {"text/html"})
    public String emailViewerPage(@PathVariable int accountId, @PathVariable int folderId, @PathVariable int emailId) throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("accountId", accountId);
        context.put("folderId", folderId);
        if (emailId == 1) {
            context.put("email",
                    new Email(
                            new EmailHeader(
                                    1,
                                    EMAIL1_SENDER,
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
                            List.of(new Attachment("sounds and music.mp3", 43534555, MimeTypeUtils.APPLICATION_OCTET_STREAM))
                    )
            );
        } else {
            context.put("email",
                    new Email(
                            new EmailHeader(
                                    2,
                                    EMAIL2_SENDER,
                                    EMAIL2_SUBJECT,
                                    EMAIL2_RECEIVEDDATE,
                                    null,
                                    false,
                                    false,
                                    false
                            ),
                            false,
                            null,
                            List.of(new Attachment("sounds and music.mp3", 43534555, MimeTypeUtils.APPLICATION_OCTET_STREAM))
                    )
            );
        }
        return PebbleRenderer.renderTemplate(context, emailViewerTemplate);
    }

    @GetMapping(value = "/accounts/{accountId}/folders/{folderId}/emails/{emailId}/body", produces = {"text/html"})
    public String emailBodyPage(@PathVariable int accountId, @PathVariable int folderId, @PathVariable int emailId) throws IOException {
        return EMAIL2_BODY;
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
