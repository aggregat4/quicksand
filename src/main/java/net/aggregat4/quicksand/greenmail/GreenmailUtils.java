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
            lastThreeMonthsStart.minusDays(3)),
        seed(
            "HTML demo: Summer Sale — up to 50% off",
            "promo@shop.example",
            """
                        Summer Sale

                        Up to 50% off selected items. Free shipping over $50.
                        """,
            """
                        <!DOCTYPE html>
                        <html>
                        <head>
                          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                        </head>
                        <body style="margin:0;padding:0;background-color:#f4f4f4;">
                          <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                              <td align="center" style="padding:20px 0;">
                                <table width="600" cellpadding="0" cellspacing="0" border="0" style="background-color:#ffffff;">
                                  <tr>
                                    <td style="padding:20px;text-align:center;">
                                      <img src="https://via.placeholder.com/200x60/ff6b6b/ffffff?text=SHOP+EXAMPLE" alt="Shop Example" width="200" style="display:block;margin:0 auto;">
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0 20px 20px;text-align:center;">
                                      <h1 style="font-family:Arial,sans-serif;font-size:32px;color:#ff6b6b;margin:0;">SUMMER SALE</h1>
                                      <p style="font-family:Arial,sans-serif;font-size:18px;color:#333333;">Up to <strong>50% off</strong> selected styles</p>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0 20px;">
                                      <table width="100%" cellpadding="10" cellspacing="0" border="0">
                                        <tr>
                                          <td width="33%" style="text-align:center;">
                                            <img src="https://via.placeholder.com/160x200/ff6b6b/ffffff?text=Item+1" alt="Product 1" width="160" style="display:block;margin:0 auto;">
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;">Sunglasses</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#ff6b6b;"><strong>$29.99</strong> <span style="text-decoration:line-through;color:#999999;">$59.99</span></p>
                                          </td>
                                          <td width="33%" style="text-align:center;">
                                            <img src="https://via.placeholder.com/160x200/4ecdc4/ffffff?text=Item+2" alt="Product 2" width="160" style="display:block;margin:0 auto;">
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;">Swimwear</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#ff6b6b;"><strong>$34.99</strong> <span style="text-decoration:line-through;color:#999999;">$69.99</span></p>
                                          </td>
                                          <td width="33%" style="text-align:center;">
                                            <img src="https://via.placeholder.com/160x200/ffe66d/333333?text=Item+3" alt="Product 3" width="160" style="display:block;margin:0 auto;">
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;">Sandals</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#ff6b6b;"><strong>$19.99</strong> <span style="text-decoration:line-through;color:#999999;">$39.99</span></p>
                                          </td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;text-align:center;">
                                      <a href="https://shop.example/sale" style="display:inline-block;padding:15px 40px;background-color:#ff6b6b;color:#ffffff;font-family:Arial,sans-serif;font-size:16px;text-decoration:none;border-radius:4px;">Shop Now</a>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;border-top:1px solid #eeeeee;text-align:center;">
                                      <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;">Free shipping on orders over $50. Sale ends Sunday.</p>
                                      <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;"><a href="https://shop.example/unsubscribe" style="color:#999999;">Unsubscribe</a> | <a href="https://shop.example/privacy" style="color:#999999;">Privacy Policy</a></p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
            todayStart.plusMinutes(10)),
        seed(
            "HTML demo: Your flight confirmation QR4217",
            "bookings@airline.example",
            """
                        Flight Confirmation QR4217

                        Departure: 10:45 from JFK Terminal 4
                        Arrival: 22:30 at LHR Terminal 5
                        """,
            """
                        <!DOCTYPE html>
                        <html>
                        <head>
                          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                        </head>
                        <body style="margin:0;padding:0;background-color:#e8f4f8;">
                          <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                              <td align="center" style="padding:20px 0;">
                                <table width="600" cellpadding="0" cellspacing="0" border="0" style="background-color:#ffffff;border-radius:8px;overflow:hidden;">
                                  <tr>
                                    <td style="background-color:#003366;padding:30px 20px;text-align:center;">
                                      <img src="https://via.placeholder.com/120x40/003366/ffffff?text=SKY+AIR" alt="Sky Air" width="120" style="display:block;margin:0 auto;">
                                      <h1 style="font-family:Arial,sans-serif;font-size:24px;color:#ffffff;margin:15px 0 0;">Your Booking is Confirmed</h1>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:30px 20px;">
                                      <table width="100%" cellpadding="0" cellspacing="0" border="0">
                                        <tr>
                                          <td width="50%" style="padding:10px;border-bottom:1px solid #eeeeee;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Flight</p>
                                            <p style="font-family:Arial,sans-serif;font-size:18px;color:#003366;margin:5px 0 0;"><strong>QR4217</strong></p>
                                          </td>
                                          <td width="50%" style="padding:10px;border-bottom:1px solid #eeeeee;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Date</p>
                                            <p style="font-family:Arial,sans-serif;font-size:18px;color:#003366;margin:5px 0 0;"><strong>14 Aug 2026</strong></p>
                                          </td>
                                        </tr>
                                        <tr>
                                          <td width="50%" style="padding:10px;border-bottom:1px solid #eeeeee;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Departure</p>
                                            <p style="font-family:Arial,sans-serif;font-size:16px;color:#333333;margin:5px 0 0;"><strong>JFK</strong> 10:45</p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:2px 0 0;">Terminal 4</p>
                                          </td>
                                          <td width="50%" style="padding:10px;border-bottom:1px solid #eeeeee;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Arrival</p>
                                            <p style="font-family:Arial,sans-serif;font-size:16px;color:#333333;margin:5px 0 0;"><strong>LHR</strong> 22:30</p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:2px 0 0;">Terminal 5</p>
                                          </td>
                                        </tr>
                                        <tr>
                                          <td colspan="2" style="padding:10px;border-bottom:1px solid #eeeeee;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Passengers</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;">Jane Doe (1 checked bag)</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:2px 0 0;">John Doe (2 checked bags)</p>
                                          </td>
                                        </tr>
                                        <tr>
                                          <td colspan="2" style="padding:10px;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Seat selection</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;">12A, 12B (Extra legroom)</p>
                                          </td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0 20px 30px;text-align:center;">
                                      <a href="https://airline.example/checkin" style="display:inline-block;padding:15px 40px;background-color:#003366;color:#ffffff;font-family:Arial,sans-serif;font-size:16px;text-decoration:none;border-radius:4px;">Online Check-in</a>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;background-color:#f8f8f8;text-align:center;">
                                      <p style="font-family:Arial,sans-serif;font-size:11px;color:#999999;">Check-in opens 24 hours before departure. Download our app for real-time updates.</p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
            todayStart.plusMinutes(15)),
        seed(
            "HTML demo: Monthly invoice — Acme Corp",
            "billing@acme.example",
            """
                        Invoice #INV-2026-0842

                        Total due: $4,250.00
                        Due date: 31 Aug 2026
                        """,
            """
                        <!DOCTYPE html>
                        <html>
                        <head>
                          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                        </head>
                        <body style="margin:0;padding:0;background-color:#f9f9f9;">
                          <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                              <td align="center" style="padding:20px 0;">
                                <table width="600" cellpadding="0" cellspacing="0" border="0" style="background-color:#ffffff;">
                                  <tr>
                                    <td style="padding:30px 20px;border-bottom:3px solid #2d5f8a;">
                                      <table width="100%" cellpadding="0" cellspacing="0" border="0">
                                        <tr>
                                          <td>
                                            <h1 style="font-family:Arial,sans-serif;font-size:20px;color:#2d5f8a;margin:0;">INVOICE</h1>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:5px 0 0;">#INV-2026-0842</p>
                                          </td>
                                          <td style="text-align:right;">
                                            <img src="https://via.placeholder.com/80x40/2d5f8a/ffffff?text=ACME" alt="Acme Corp" width="80" style="display:block;margin-left:auto;">
                                          </td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;">
                                      <table width="100%" cellpadding="0" cellspacing="0" border="0">
                                        <tr>
                                          <td style="padding-bottom:20px;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Billed to</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;"><strong>Example Client Ltd</strong></p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:2px 0 0;">123 Business Street, Suite 400<br>London, EC1A 1BB</p>
                                          </td>
                                          <td style="text-align:right;">
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:0;">Invoice date</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;"><strong>1 Aug 2026</strong></p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#999999;margin:15px 0 0;">Due date</p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;"><strong>31 Aug 2026</strong></p>
                                          </td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:0 20px;">
                                      <table width="100%" cellpadding="10" cellspacing="0" border="0" style="border-top:1px solid #eeeeee;">
                                        <tr style="background-color:#f5f5f5;">
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;"><strong>Description</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;"><strong>Qty</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;"><strong>Rate</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;"><strong>Amount</strong></td>
                                        </tr>
                                        <tr>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;border-bottom:1px solid #eeeeee;">Professional services — August 2026</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">80</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">$50.00</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">$4,000.00</td>
                                        </tr>
                                        <tr>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;border-bottom:1px solid #eeeeee;">Software license renewal</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">1</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">$250.00</td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;border-bottom:1px solid #eeeeee;">$250.00</td>
                                        </tr>
                                        <tr>
                                          <td colspan="3" style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;padding-top:10px;"><strong>Subtotal</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;padding-top:10px;">$4,250.00</td>
                                        </tr>
                                        <tr>
                                          <td colspan="3" style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;"><strong>VAT (0%)</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:12px;color:#333333;text-align:right;">$0.00</td>
                                        </tr>
                                        <tr>
                                          <td colspan="3" style="font-family:Arial,sans-serif;font-size:16px;color:#2d5f8a;text-align:right;padding-top:10px;"><strong>Total Due</strong></td>
                                          <td style="font-family:Arial,sans-serif;font-size:16px;color:#2d5f8a;text-align:right;padding-top:10px;"><strong>$4,250.00</strong></td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;text-align:center;">
                                      <a href="https://acme.example/pay/INV-2026-0842" style="display:inline-block;padding:15px 40px;background-color:#2d5f8a;color:#ffffff;font-family:Arial,sans-serif;font-size:16px;text-decoration:none;border-radius:4px;">Pay Invoice</a>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;border-top:1px solid #eeeeee;">
                                      <p style="font-family:Arial,sans-serif;font-size:11px;color:#999999;text-align:center;">Questions? Contact billing@acme.example</p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
            weekStart.plusMinutes(5)),
        seed(
            "HTML demo: Security alert — new device sign-in",
            "security@cloudsync.example",
            """
                        Security Alert

                        A new device signed in to your CloudSync account from Berlin, DE.
                        If this was you, no action is needed.
                        """,
            """
                        <!DOCTYPE html>
                        <html>
                        <head>
                          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                        </head>
                        <body style="margin:0;padding:0;background-color:#fafafa;">
                          <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                              <td align="center" style="padding:20px 0;">
                                <table width="600" cellpadding="0" cellspacing="0" border="0" style="background-color:#ffffff;border:1px solid #e0e0e0;">
                                  <tr>
                                    <td style="padding:30px 20px;text-align:center;border-bottom:1px solid #e0e0e0;">
                                      <img src="https://via.placeholder.com/64x64/d32f2f/ffffff?text=!" alt="Alert" width="64" style="display:block;margin:0 auto;">
                                      <h1 style="font-family:Arial,sans-serif;font-size:22px;color:#d32f2f;margin:15px 0 0;">New Device Sign-in Detected</h1>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;">
                                      <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;line-height:1.6;">Hi there,</p>
                                      <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;line-height:1.6;">We noticed a new sign-in to your CloudSync account on a device we don't recognize.</p>
                                      <table width="100%" cellpadding="15" cellspacing="0" border="0" style="background-color:#fff3e0;border-left:4px solid #ff9800;margin:20px 0;">
                                        <tr>
                                          <td>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:0;"><strong>When</strong></p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 15px;">26 Apr 2026 at 14:32 UTC</p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:0;"><strong>Where</strong></p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 15px;">Berlin, Germany (IP: 203.0.113.42)</p>
                                            <p style="font-family:Arial,sans-serif;font-size:12px;color:#666666;margin:0;"><strong>Device</strong></p>
                                            <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;margin:5px 0 0;">Chrome on macOS</p>
                                          </td>
                                        </tr>
                                      </table>
                                      <p style="font-family:Arial,sans-serif;font-size:14px;color:#333333;line-height:1.6;"><strong>Was this you?</strong></p>
                                      <table width="100%" cellpadding="0" cellspacing="0" border="0">
                                        <tr>
                                          <td style="padding:10px 0;text-align:center;">
                                            <a href="https://cloudsync.example/verify" style="display:inline-block;padding:12px 30px;background-color:#4caf50;color:#ffffff;font-family:Arial,sans-serif;font-size:14px;text-decoration:none;border-radius:4px;margin-right:10px;">Yes, it was me</a>
                                            <a href="https://cloudsync.example/secure" style="display:inline-block;padding:12px 30px;background-color:#d32f2f;color:#ffffff;font-family:Arial,sans-serif;font-size:14px;text-decoration:none;border-radius:4px;">No, secure my account</a>
                                          </td>
                                        </tr>
                                      </table>
                                    </td>
                                  </tr>
                                  <tr>
                                    <td style="padding:20px;border-top:1px solid #e0e0e0;text-align:center;">
                                      <p style="font-family:Arial,sans-serif;font-size:11px;color:#999999;">You received this email because you have security alerts enabled. <a href="https://cloudsync.example/settings" style="color:#999999;">Manage preferences</a></p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
            weekStart.plusMinutes(10)));
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
