package net.aggregat4.quicksand.webservice;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EmailWebServiceActionTest {

  private static WebServer webServer;
  private static HttpClient httpClient;
  private static DbEmailRepository emailRepository;
  private static int firstMessageId;
  private static int secondMessageId;
  private static String baseUrl;

  @BeforeAll
  static void startServer() throws IOException, SQLException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);

    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    Account account = new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    accountRepository.createAccountIfNew(account);
    int accountId = accountRepository.getAccounts().getFirst().id();

    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    NamedFolder folder =
        folderRepository.createFolder(accountRepository.getAccount(accountId), "INBOX");

    DbActorRepository actorRepository = new DbActorRepository(ds);
    emailRepository = new DbEmailRepository(ds, actorRepository);

    List<Actor> actors = List.of(new Actor(ActorType.SENDER, "a@b.com", Optional.of("A")));
    ZonedDateTime now = ZonedDateTime.now();
    EmailHeader header1 =
        new EmailHeader(
            -1, 1L, actors, "Subject 1", now, 0L, now, 0L, "Excerpt", false, false, false);
    Email email1 = new Email(header1, true, "Body 1", Collections.emptyList());
    firstMessageId = emailRepository.addMessage(folder.id(), email1);

    EmailHeader header2 =
        new EmailHeader(
            -1, 2L, actors, "Subject 2", now, 0L, now, 0L, "Excerpt", false, false, true);
    Email email2 = new Email(header2, true, "Body 2", Collections.emptyList());
    secondMessageId = emailRepository.addMessage(folder.id(), email2);

    DbDraftRepository draftRepository = new DbDraftRepository(ds);
    AttachmentService attachmentService = new AttachmentService(new DbAttachmentRepository(ds));
    EmailService emailService = new EmailService(emailRepository);
    DraftService draftService =
        new DraftService(
            draftRepository, emailRepository, attachmentService, Clock.systemDefaultZone());
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            ds,
            accountRepository,
            draftRepository,
            new DbAttachmentRepository(ds),
            new DbOutboundMessageRepository(ds),
            Clock.systemDefaultZone());

    HttpRouting.Builder routing =
        HttpRouting.builder()
            .register(
                "/emails",
                new EmailWebService(
                    emailService, draftService, attachmentService, outboundMessageService));

    webServer = WebServer.builder().port(0).host("127.0.0.1").routing(routing).build().start();

    httpClient = HttpClient.newHttpClient();
    baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
  }

  @AfterAll
  static void stopServer() {
    if (webServer != null) {
      webServer.stop();
    }
  }

  @Test
  void markReadAndUnreadRedirectsAndUpdatesState() throws IOException, InterruptedException {
    assertFalse(emailRepository.findById(firstMessageId).orElseThrow().header().read());

    HttpRequest markRead =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + firstMessageId + "&email_action_mark_read=Mark+Read"))
            .build();
    HttpResponse<String> readResponse =
        httpClient.send(markRead, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, readResponse.statusCode());
    assertEquals(
        baseUrl + "/accounts/1", readResponse.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(firstMessageId).orElseThrow().header().read());

    HttpRequest markUnread =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + firstMessageId + "&email_action_mark_unread=Mark+Unread"))
            .build();
    HttpResponse<String> unreadResponse =
        httpClient.send(markUnread, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, unreadResponse.statusCode());

    assertFalse(emailRepository.findById(firstMessageId).orElseThrow().header().read());
  }

  @Test
  void deleteRemovesMessageAndRedirectsToReferer() throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(firstMessageId).isPresent());

    HttpRequest delete =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + firstMessageId + "&email_action_delete=Delete"))
            .build();
    HttpResponse<String> response = httpClient.send(delete, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals(baseUrl + "/accounts/1", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(firstMessageId).isEmpty());
  }

  @Test
  void deleteFromViewerRedirectsToHome() throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(secondMessageId).isPresent());

    HttpRequest delete =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/emails/" + secondMessageId + "/viewer")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + secondMessageId + "&email_action_delete=Delete"))
            .build();
    HttpResponse<String> response = httpClient.send(delete, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals("/", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(secondMessageId).isEmpty());
  }

  @Test
  void bulkMarkReadUpdatesMultipleEmails() throws IOException, InterruptedException {
    assertFalse(emailRepository.findById(firstMessageId).orElseThrow().header().read());
    assertTrue(emailRepository.findById(secondMessageId).orElseThrow().header().read());

    HttpRequest markRead =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select="
                        + firstMessageId
                        + "&email_select="
                        + secondMessageId
                        + "&email_action_mark_read=Mark+Read"))
            .build();
    HttpResponse<String> response = httpClient.send(markRead, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());

    assertTrue(emailRepository.findById(firstMessageId).orElseThrow().header().read());
    assertTrue(emailRepository.findById(secondMessageId).orElseThrow().header().read());
  }
}
