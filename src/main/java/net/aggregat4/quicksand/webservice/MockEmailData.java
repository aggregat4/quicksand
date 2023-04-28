package net.aggregat4.quicksand.webservice;

import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.helidon.common.http.MediaType;

public class MockEmailData {
    public static final Actor ACCOUNT1_OWNER = new Actor(ActorType.SENDER, "me@example.com", Optional.of("me"));
    public static final Actor EMAIL1_SENDER = new Actor(ActorType.SENDER, "someone@somewhere.com", Optional.of("Someone"));
    public static final Actor EMAIL1_RECIPIENT = new Actor(ActorType.TO, "me@example.com", Optional.of("Me Doe"));
    public static final Actor EMAIL1_CC1 = new Actor(ActorType.CC, "janecc@foobar.tld", Optional.of("Jane Carbon Copy"));
    public static final Actor EMAIL1_CC2 = new Actor(ActorType.CC, "johnny234@gmail.com", Optional.empty());
    public static final String EMAIL1_SUBJECT = "Hey there how are you?";
    public static final ZonedDateTime EMAIL1_RECEIVEDDATE = ZonedDateTime.now();
    public static final ZonedDateTime EMAIL1_SENTDATE = ZonedDateTime.now().minus(5, ChronoUnit.MINUTES);
    public static final Actor EMAIL2_SENDER = new Actor(ActorType.SENDER, "foo@bar.net", Optional.empty());
    public static final Actor EMAIL2_RECIPIENT = new Actor(ActorType.TO, "me@example.org", Optional.of("Doe, Me"));
    public static final String EMAIL2_SUBJECT = "Foo du fafa";
    public static final ZonedDateTime EMAIL2_RECEIVEDDATE = ZonedDateTime.now().minus(3, ChronoUnit.MINUTES);
    public static final ZonedDateTime EMAIL2_SENTDATE = ZonedDateTime.now().minus(13, ChronoUnit.MINUTES);
    static final Attachment ATTACHMENT1 = new Attachment(1, "sounds and music.mp3", 43534555, MediaType.builder().type("audio").subtype("mpeg").build());
     static final String sampleMp3Resource = "/sample-3s.mp3";
    static final Email PLAINTEXT_EMAIL = new Email(
            new EmailHeader(
                    1,
                    1,
                    List.of(EMAIL1_SENDER, EMAIL1_RECIPIENT, EMAIL1_CC1, EMAIL1_CC2),
                    EMAIL1_SUBJECT,
                    EMAIL1_RECEIVEDDATE,
                    EMAIL1_RECEIVEDDATE.toEpochSecond(),
                    EMAIL1_SENTDATE,
                    EMAIL1_SENTDATE.toEpochSecond(),
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
                    2,
                    List.of(EMAIL2_SENDER, EMAIL2_RECIPIENT),
                    EMAIL2_SUBJECT,
                    EMAIL2_SENTDATE,
                    EMAIL2_SENTDATE.toEpochSecond(),
                    EMAIL2_RECEIVEDDATE,
                    EMAIL2_RECEIVEDDATE.toEpochSecond(),
                    null,
                    false,
                    false,
                    false
            ),
            false,
            null,
            Collections.emptyList());

    static final Email NEW_EMAIL = new Email(
            new EmailHeader(
                    42,
                    42,
                    List.of(ACCOUNT1_OWNER),
                    null,
                    null,
                    0,
                    null,
                    0,
                    null,
                    false,
                    false,
                    false
            ),
            true,
            "",
            Collections.emptyList()
    );
    static final Email REPLY_EMAIL = new Email(
            new EmailHeader(
                    100,
                    100,
                    List.of(
                            ACCOUNT1_OWNER,
                            Actor.findActor(PLAINTEXT_EMAIL.header().actors(), ActorType.SENDER)),
                    "Re: " + PLAINTEXT_EMAIL.header().subject(),
                    null,
                    0,
                    null,
                    0,
                    "",
                    false,
                    false,
                    false
            ),
            true,
            quoteEmailBody(PLAINTEXT_EMAIL.body()),
            Collections.emptyList()
    );

    private static String quoteEmailBody(String body) {
        return body
                .lines()
                // empty quoted lines do not get a space added and if the line is already quoted we just increase the quote level
                // all other lines have '> ' prefixed
                // see also https://en.wikipedia.org/wiki/Posting_style
                .map(line -> line.isEmpty() || line.startsWith(">") ? ">" : "> " + line)
                .collect(Collectors.joining("\n"));
    }

    static final Email FORWARD_EMAIL = new Email(
            new EmailHeader(
                    200,
                    200,
                    List.of(ACCOUNT1_OWNER),
                    "Fwd: " + PLAINTEXT_EMAIL.header().subject(),
                    null,
                    0,
                    null,
                    0,
                    "",
                    false,
                    false,
                    false
            ),
            true,
            createForwardEmailBody(PLAINTEXT_EMAIL),
            Collections.emptyList()
    );

    private static String createForwardEmailBody(Email originalEmail) {
        List<Actor> actors = originalEmail.header().actors();
        return """
                
                -------- Original Message --------                              
                """ +
                "From: %s\n".formatted(Actor.findActor(actors, ActorType.SENDER)) +
                "Sent: %s\n".formatted(originalEmail.header().longFormattedSentDate()) +
                "To: %s\n".formatted(Actor.findActor(actors, ActorType.TO)) +
                "Subject: %s\n".formatted(originalEmail.header().subject()) +
                "\n\n\n" +
                originalEmail.body();
    }

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

//    private static List<EmailGroup> getMockEmailGroups(boolean returnSearchResults) {
//        if (returnSearchResults) {
//            // The search results are "just" "normal" paginated results but there are some special things
//            // - there is probably no count to the search (too expensive)
//            // - the following strings/texts can have highlighted snippets: sender name, sender email, subject, bodyExcerpt
//            // - we can assume that those strings were escaped and then highlighted, so they can be displayed raw
//            return EmailGroup.createNoGroupEmailgroup(List.of(
//                            new EmailHeader(1, 1, List.of(MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT), MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE,MockEmailData.EMAIL1_RECEIVEDDATE.toEpochSecond(),  MockEmailData.EMAIL1_SENTDATE, MockEmailData.EMAIL1_SENTDATE.toEpochSecond(), "Hey you, this is me <mark>John</mark> sending you this somewhere what is this and this snippet just goes on", false, true, false),
//                            new EmailHeader(3, 3, List.of(new Actor(ActorType.SENDER, "izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT), "This is the greatest <mark>john</mark>-like thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), 0, ZonedDateTime.now().minus(17, ChronoUnit.MINUTES), 0, "Dear Mr. Michalson, Mr. <mark>John</mark> This is the winter of my discontent. And I hope you are fine.", false, false, true),
//                            new EmailHeader(4, 4, List.of(new Actor(ActorType.SENDER, "ceo@ibm.com", Optional.of("<mark>John</mark> Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT), "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
//                            new EmailHeader(5, 5, List.of(new Actor(ActorType.SENDER, "<mark>john</mark>@waterman.org", Optional.of("<mark>John</mark> Doe")), MockEmailData.EMAIL1_RECIPIENT), "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true)
//                    )
//            );
//        } else {
//            return List.of(
//                    new EmailGroup.TodayEmailGroup(List.of(
//                            new EmailHeader(1, 1, List.of(MockEmailData.EMAIL1_SENDER, MockEmailData.EMAIL1_RECIPIENT), MockEmailData.EMAIL1_SUBJECT, MockEmailData.EMAIL1_RECEIVEDDATE, 0, MockEmailData.EMAIL1_SENTDATE, 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, false),
//                            new EmailHeader(2, 2, List.of(MockEmailData.EMAIL2_SENDER, MockEmailData.EMAIL2_RECIPIENT), MockEmailData.EMAIL2_SUBJECT, MockEmailData.EMAIL2_RECEIVEDDATE, 0, MockEmailData.EMAIL2_SENTDATE, 0, "Asd askl; sdkljhs ldkjfhaslkdjfhalksdjfh aslkd  falskjd alskdfhqw qwe ", false, false, false))),
//                    new EmailGroup.ThisWeekEmailGroup(List.of(
//                            new EmailHeader(3, 3, List.of(new Actor(ActorType.SENDER, "izzi342@gmail.com", Optional.of("Eddie Izzard")), MockEmailData.EMAIL1_RECIPIENT), "This is the greatest thing ever!", ZonedDateTime.now().minus(44, ChronoUnit.MINUTES), 0, ZonedDateTime.now().minus(47, ChronoUnit.MINUTES), 0, "Dear Mr. Michalson, This is the winter of my discontent. And I hope you are fine.", false, false, true))),
//                    new EmailGroup.ThisMonthEmailGroup(List.of(
//                            new EmailHeader(4, 4, List.of(new Actor(ActorType.SENDER, "ceo@ibm.com", Optional.of("John Hockenberry von Hockenstein")), MockEmailData.EMAIL1_RECIPIENT), "Hocky my hockface", ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, ZonedDateTime.now().minus(2, ChronoUnit.HOURS), 0, "Hey you, this is me sending you this somewhere what is this and this snippet just goes on", false, true, true),
//                            new EmailHeader(5, 5, List.of(new Actor(ActorType.SENDER, "john@waterman.org", Optional.of("John Doe")), MockEmailData.EMAIL1_RECIPIENT), "Hi", ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(3, ChronoUnit.DAYS), 0, "JKHGajkls glasjkdfgh djshfsdklj fhskdjlfh asdkljfh asdkljf qweuihawioeusdv bj", true, false, true),
//                            new EmailHeader(6, 6, List.of(new Actor(ActorType.SENDER, "whatevs@mail.org", Optional.of("Evan Watts")), MockEmailData.EMAIL1_RECIPIENT), "Dude, wassup!", ZonedDateTime.now().minus(7, ChronoUnit.DAYS), 0, ZonedDateTime.now().minus(7, ChronoUnit.DAYS), 0, "And now my dear there is a chance that we may meet again in fields", false, false, true)))
//            );
//        }
//    }



}
