package net.aggregat4.quicksand.services;

import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.helidon.common.http.MediaType;

public class MockEmailData {
    public static final Actor EMAIL1_SENDER = new Actor("someone@somewhere.com", Optional.of("Someone"));
    public static final Actor EMAIL1_RECIPIENT = new Actor("me@example.com", Optional.of("Me Doe"));
    public static final String EMAIL1_SUBJECT = "Hey there how are you?";
    public static final ZonedDateTime EMAIL1_RECEIVEDDATE = ZonedDateTime.now();
    public static final Actor EMAIL2_SENDER = new Actor("foo@bar.net", Optional.empty());
    public static final Actor EMAIL2_RECIPIENT = new Actor("me@example.org", Optional.of("Doe, Me"));
    public static final String EMAIL2_SUBJECT = "Foo du fafa";
    public static final ZonedDateTime EMAIL2_RECEIVEDDATE = ZonedDateTime.now().minus(3, ChronoUnit.MINUTES);
    static final Attachment ATTACHMENT1 = new Attachment(1, "sounds and music.mp3", 43534555, MediaType.builder().type("audio").subtype("mpeg").build());
     static final String sampleMp3Resource = "/sample-3s.mp3";
    static final Email PLAINTEXT_EMAIL = new Email(
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
    );
    static final Email HTML_EMAIL = new Email(
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
            Collections.emptyList());
    static final String HTML_EMAIL_BODY = """
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
