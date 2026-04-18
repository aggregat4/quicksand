package net.aggregat4.quicksand.greenmail;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.GroupedPeriod;

public class GreenmailUtils {
  private record DemoMessageTemplate(
      String subject, String from, String plainTextBody, String htmlBody) {}

  private record DemoMessageSeed(DemoMessageTemplate template, ZonedDateTime receivedAt) {}

  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "testpassword";
  private static final Account account =
      new Account(
          1, "test", "localhost", 4143, USERNAME, PASSWORD, "localhost", 4025, USERNAME, PASSWORD);
  private static final String EMAIL = "testuser@localhost";
  private static final int DUPLICATE_TIMESTAMP_CLUSTER_SIZE = 6;

  public static Store getImapStore(GreenMailOperations greenMail) throws MessagingException {
    Session imapSession = greenMail.getImap().createSession();
    Store store = imapSession.getStore("imap");
    store.connect(USERNAME, PASSWORD);
    return store;
  }

  public static void deliverOneMessage(
      GreenMailOperations greenMail, String subject, String body, String from, String to) {
    deliverMessages(greenMail, subject, body, from, to, 1);
  }

  public static void deliverMessages(
      GreenMailOperations greenMail,
      String subject,
      String body,
      String from,
      String to,
      int count) {
    MimeMessage message =
        GreenMailUtil.createTextEmail(
            to, from, subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
    GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
    for (int i = 0; i < count; i++) {
      user.deliver(message);
    }
  }

  public static void deliverMessages(GreenMailOperations greenMail, int count) {
    String to = "foo@bar.com";
    GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
    for (int i = 0; i < count; i++) {
      String subject = GreenMailUtil.random(20);
      String body = GreenMailUtil.random(50);
      String from = GreenMailUtil.random(10) + "@example.com";
      MimeMessage message =
          GreenMailUtil.createTextEmail(
              to, from, subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
      user.deliver(message);
    }
  }

  public static void deliverDemoMessages(GreenMailOperations greenMail, int count, Clock clock) {
    GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
    MailFolder inbox = getInbox(greenMail, user);
    List<DemoMessageSeed> demoSeeds = buildDemoSeeds(count, clock);

    for (int i = 0; i < demoSeeds.size(); i++) {
      DemoMessageSeed demoSeed = demoSeeds.get(i);
      ZonedDateTime sentAt = demoSeed.receivedAt().minusMinutes(5L + (i % 17L));
      MimeMessage message = createDemoMessage(greenMail, demoSeed.template(), sentAt);
      appendMessage(inbox, message, demoSeed.receivedAt());
    }
  }

  public static Account getAccount() {
    return account;
  }

  private static MailFolder getInbox(GreenMailOperations greenMail, GreenMailUser user) {
    try {
      return greenMail.getManagers().getImapHostManager().getInbox(user);
    } catch (FolderException e) {
      throw new IllegalStateException("Failed to access demo inbox", e);
    }
  }

  private static List<DemoMessageSeed> buildDemoSeeds(int count, Clock clock) {
    List<DemoMessageSeed> demoSeeds = new ArrayList<>();
    List<DemoMessageSeed> boundarySeeds = buildBoundarySeeds(clock);
    if (count <= boundarySeeds.size()) {
      return boundarySeeds.subList(0, count);
    }

    demoSeeds.addAll(boundarySeeds);
    ZonedDateTime oldestBoundaryTimestamp = boundarySeeds.getLast().receivedAt();
    int fillerCount = count - boundarySeeds.size();
    for (int i = 0; i < fillerCount; i++) {
      int clusterIndex = i / DUPLICATE_TIMESTAMP_CLUSTER_SIZE;
      ZonedDateTime receivedAt =
          oldestBoundaryTimestamp.minusHours(6).minusHours(12L * clusterIndex);
      demoSeeds.add(new DemoMessageSeed(fillerTemplateForIndex(i), receivedAt));
    }
    return demoSeeds;
  }

  private static List<DemoMessageSeed> buildBoundarySeeds(Clock clock) {
    ZonedDateTime now = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    LocalDate today = LocalDate.now(clock);
    ZonedDateTime todayStart = today.atStartOfDay(clock.getZone());
    ZonedDateTime weekStart = GroupedPeriod.getBeginningOfWeek(clock).atStartOfDay(clock.getZone());
    ZonedDateTime lastWeekStart = weekStart.minusWeeks(1);
    ZonedDateTime monthStart =
        GroupedPeriod.getBeginningOfMonth(clock).atStartOfDay(clock.getZone());
    ZonedDateTime lastThreeMonthsStart = monthStart.minusMonths(2);

    return List.of(
        seed(
            "Today latest - welcome to the demo inbox",
            "hello@quicksand.demo",
            """
                        This inbox is seeded around exact temporal boundaries.

                        It is intended to exercise grouping, sorting and paging edge cases end to end.
                        """,
            null,
            now.minusMinutes(5)),
        seed(
            "HTML demo: Product launch digest",
            "launches@example.com",
            """
                        Launch Digest

                        Migration window: Tuesday 09:00 UTC
                        Rollout status: Green for desktop, amber for mobile
                        """,
            """
                        <html>
                          <body>
                            <h1>Launch Digest</h1>
                            <p><strong>Migration window:</strong> Tuesday 09:00 UTC</p>
                            <p>Rollout status: <em>Green</em> for desktop, amber for mobile.</p>
                            <table>
                              <tr><td>Region</td><td>Status</td></tr>
                              <tr><td>EU</td><td>Ready</td></tr>
                              <tr><td>US</td><td>Monitoring</td></tr>
                            </table>
                          </body>
                        </html>
                        """,
            todayStart.plusMinutes(2)),
        seed(
            "Today exact - start of day",
            "boundaries@example.com",
            """
                        This message sits exactly at the start of the current day.
                        """,
            null,
            todayStart),
        seed(
            "This week boundary - one minute before today",
            "boundaries@example.com",
            """
                        This message should group as This Week.
                        """,
            null,
            todayStart.minusMinutes(1)),
        seed(
            "This week exact - start of week",
            "boundaries@example.com",
            """
                        This message sits exactly on the start-of-week boundary.
                        """,
            null,
            weekStart),
        seed(
            "Last week boundary - one minute before this week",
            "boundaries@example.com",
            """
                        This message should group as Last Week.
                        """,
            null,
            weekStart.minusMinutes(1)),
        seed(
            "Last week exact - start of last week",
            "boundaries@example.com",
            """
                        This message sits exactly on the start of last week.
                        """,
            null,
            lastWeekStart),
        seed(
            "This month boundary - one minute before last week",
            "boundaries@example.com",
            """
                        This message should group as This Month.
                        """,
            null,
            lastWeekStart.minusMinutes(1)),
        seed(
            "This month exact - start of month",
            "boundaries@example.com",
            """
                        This message sits exactly on the start of the current month.
                        """,
            null,
            monthStart),
        seed(
            "Last three months boundary - one minute before this month",
            "boundaries@example.com",
            """
                        This message should group as Last Three Months.
                        """,
            null,
            monthStart.minusMinutes(1)),
        seed(
            "Last three months exact - start of window",
            "boundaries@example.com",
            """
                        This message sits exactly on the start of the last-three-month window.
                        """,
            null,
            lastThreeMonthsStart),
        seed(
            "Older boundary - one minute before last three months",
            "boundaries@example.com",
            """
                        This message should group as Older.
                        """,
            null,
            lastThreeMonthsStart.minusMinutes(1)),
        seed(
            "HTML demo: Quarterly update",
            "news@example.com",
            """
                        Quarterly update

                        Revenue is up and the backlog is trending down.
                        """,
            """
                        <html>
                          <body>
                            <h1>Quarterly Update</h1>
                            <p>Revenue is up and the backlog is trending down.</p>
                            <ul>
                              <li>Inbox paging is stable</li>
                              <li>Viewer content is persisted</li>
                              <li>Draft persistence is still pending</li>
                            </ul>
                          </body>
                        </html>
                        """,
            lastThreeMonthsStart.minusDays(3)));
  }

  private static DemoMessageSeed seed(
      String subject,
      String from,
      String plainTextBody,
      String htmlBody,
      ZonedDateTime receivedAt) {
    return new DemoMessageSeed(
        new DemoMessageTemplate(subject, from, plainTextBody, htmlBody), receivedAt);
  }

  private static DemoMessageTemplate fillerTemplateForIndex(int index) {
    int demoNumber = index + 1;
    String subject = "Duplicate timestamp sample %03d".formatted(demoNumber);
    String from = "sender%03d@example.com".formatted((index % 23) + 1);
    String plainTextBody =
        """
                Duplicate timestamp sample %d

                This seeded message shares a received timestamp with neighbouring messages so paging tests can detect skips or duplicates.
                Cluster: %d
                """
            .formatted(demoNumber, (index / DUPLICATE_TIMESTAMP_CLUSTER_SIZE) + 1);
    return new DemoMessageTemplate(subject, from, plainTextBody, null);
  }

  private static MimeMessage createDemoMessage(
      GreenMailOperations greenMail, DemoMessageTemplate template, ZonedDateTime sentAt) {
    try {
      MimeMessage message = new MimeMessage(greenMail.getSmtp().createSession());
      message.setFrom(new InternetAddress(template.from()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL));
      message.setSubject(template.subject(), StandardCharsets.UTF_8.name());
      message.setSentDate(Date.from(sentAt.toInstant()));
      if (template.htmlBody() == null) {
        message.setText(template.plainTextBody(), StandardCharsets.UTF_8.name());
      } else {
        MimeBodyPart plainPart = new MimeBodyPart();
        plainPart.setText(template.plainTextBody(), StandardCharsets.UTF_8.name());
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(template.htmlBody(), "text/html; charset=UTF-8");
        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(plainPart);
        multipart.addBodyPart(htmlPart);
        message.setContent(multipart);
      }
      message.saveChanges();
      return message;
    } catch (MessagingException e) {
      throw new IllegalStateException("Failed to create demo message", e);
    }
  }

  private static void appendMessage(
      MailFolder inbox, MimeMessage message, ZonedDateTime receivedAt) {
    inbox.appendMessage(message, new Flags(), Date.from(receivedAt.toInstant()));
  }
}
