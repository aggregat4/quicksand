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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmailWebServiceActionTest {

  private static WebServer webServer;
  private static HttpClient httpClient;
  private static DataSource dataSource;
  private static DbEmailRepository emailRepository;
  private static int firstMessageId;
  private static int secondMessageId;
  private static int thirdMessageId;
  private static int fourthMessageId;
  private static int fifthMessageId;
  private static int accountId;
  private static int otherAccountId;
  private static int inboxFolderId;
  private static int targetFolderId;
  private static int trashFolderId;
  private static int otherAccountFolderId;
  private static DbAccountFolderMappingRepository mappingRepository;
  private static String baseUrl;

  @BeforeAll
  static void startServer() throws IOException, SQLException {
    dataSource = DbTestUtils.getTempSqlite();
    migrateDb(dataSource);

    DbAccountRepository accountRepository = new DbAccountRepository(dataSource);
    Account account = new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    accountRepository.createAccountIfNew(account);
    accountId = accountRepository.getAccounts().getFirst().id();

    DbFolderRepository folderRepository = new DbFolderRepository(dataSource);
    Account savedAccount = accountRepository.getAccount(accountId);
    NamedFolder folder = folderRepository.createFolder(savedAccount, "INBOX");
    inboxFolderId = folder.id();
    targetFolderId = folderRepository.createFolder(savedAccount, "Target").id();
    int archiveFolderId =
        folderRepository
            .createFolder(savedAccount, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 1L)
            .id();
    trashFolderId =
        folderRepository
            .createFolder(savedAccount, "Trash", "Trash", FolderSpecialUse.TRASH, 2L)
            .id();
    int junkFolderId =
        folderRepository.createFolder(savedAccount, "Spam", "Spam", FolderSpecialUse.JUNK, 3L).id();
    mappingRepository = new DbAccountFolderMappingRepository(dataSource);
    mappingRepository.save(
        accountId,
        FolderSpecialUse.ARCHIVE,
        archiveFolderId,
        "Archive",
        FolderMappingStatus.USER_CONFIRMED);
    mappingRepository.save(
        accountId,
        FolderSpecialUse.TRASH,
        trashFolderId,
        "Trash",
        FolderMappingStatus.USER_CONFIRMED);
    mappingRepository.save(
        accountId, FolderSpecialUse.JUNK, junkFolderId, "Spam", FolderMappingStatus.USER_CONFIRMED);

    Account otherAccount = new Account(-1, "Other", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    accountRepository.createAccountIfNew(otherAccount);
    otherAccountId =
        accountRepository.getAccounts().stream()
            .filter(existingAccount -> existingAccount.name().equals("Other"))
            .findFirst()
            .orElseThrow()
            .id();
    otherAccountFolderId =
        folderRepository
            .createFolder(accountRepository.getAccount(otherAccountId), "Other INBOX")
            .id();

    DbActorRepository actorRepository = new DbActorRepository(dataSource);
    emailRepository = new DbEmailRepository(dataSource, actorRepository);

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

    EmailHeader header3 =
        new EmailHeader(
            -1, 3L, actors, "Subject 3", now, 0L, now, 0L, "Excerpt", false, false, true);
    Email email3 = new Email(header3, true, "Body 3", Collections.emptyList());
    thirdMessageId = emailRepository.addMessage(folder.id(), email3);

    EmailHeader header4 =
        new EmailHeader(
            -1, 4L, actors, "Subject 4", now, 0L, now, 0L, "Excerpt", false, false, true);
    Email email4 = new Email(header4, true, "Body 4", Collections.emptyList());
    fourthMessageId = emailRepository.addMessage(folder.id(), email4);

    EmailHeader header5 =
        new EmailHeader(
            -1, 5L, actors, "Subject 5", now, 0L, now, 0L, "Excerpt", false, false, true);
    Email email5 = new Email(header5, true, "Body 5", Collections.emptyList());
    fifthMessageId = emailRepository.addMessage(folder.id(), email5);

    DbDraftRepository draftRepository = new DbDraftRepository(dataSource);
    AttachmentService attachmentService =
        new AttachmentService(new DbAttachmentRepository(dataSource));
    EmailService emailService = new EmailService(emailRepository);
    DraftService draftService =
        new DraftService(
            draftRepository, emailRepository, attachmentService, 5L, Clock.systemDefaultZone());
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            dataSource,
            accountRepository,
            draftRepository,
            new DbAttachmentRepository(dataSource),
            new DbOutboundMessageRepository(dataSource),
            emailRepository,
            Clock.systemDefaultZone());
    AccountFolderMappingService accountFolderMappingService =
        new AccountFolderMappingService(mappingRepository, folderRepository, accountRepository);

    HttpRouting.Builder routing =
        HttpRouting.builder()
            .register(
                "/emails",
                new EmailWebService(
                    emailService,
                    draftService,
                    attachmentService,
                    outboundMessageService,
                    accountFolderMappingService));

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
  @Order(1)
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
  @Order(2)
  void viewerMarksUnreadMessageAsRead() throws IOException, InterruptedException {
    emailRepository.updateRead(firstMessageId, false);
    assertFalse(emailRepository.findById(firstMessageId).orElseThrow().header().read());

    HttpRequest viewer =
        HttpRequest.newBuilder(
                URI.create(baseUrl + "/emails/" + firstMessageId + "/viewer?showImages=false"))
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(viewer, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertTrue(emailRepository.findById(firstMessageId).orElseThrow().header().read());
  }

  @Test
  @Order(4)
  void markSpamMovesMessageToSpamFolderAndRedirectsFromViewer()
      throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(thirdMessageId).isPresent());
    int inboxCountBefore = emailRepository.getMessageCount(accountId, inboxFolderId);

    HttpRequest spam =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/emails/" + thirdMessageId + "/viewer")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + thirdMessageId + "&email_action_mark_spam=Mark+Spam"))
            .build();
    HttpResponse<String> response = httpClient.send(spam, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals("/", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(thirdMessageId).isPresent());
    assertEquals(inboxCountBefore - 1, emailRepository.getMessageCount(accountId, inboxFolderId));
  }

  @Test
  @Order(5)
  void moveUpdatesSelectedMessagesToTargetFolder() throws IOException, InterruptedException {
    int inboxCountBefore = emailRepository.getMessageCount(accountId, inboxFolderId);
    int targetCountBefore = emailRepository.getMessageCount(accountId, targetFolderId);

    HttpRequest move =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select="
                        + fourthMessageId
                        + "&email_select="
                        + fifthMessageId
                        + "&target_folder="
                        + targetFolderId
                        + "&email_action_move=Move"))
            .build();
    HttpResponse<String> response = httpClient.send(move, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals(baseUrl + "/accounts/1", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(fourthMessageId).isPresent());
    assertTrue(emailRepository.findById(fifthMessageId).isPresent());
    assertEquals(inboxCountBefore - 2, emailRepository.getMessageCount(accountId, inboxFolderId));
    assertEquals(targetCountBefore + 2, emailRepository.getMessageCount(accountId, targetFolderId));
    assertEquals(2, queuedActionCountForType(MailboxActionType.MOVE));
  }

  @Test
  @Order(6)
  void moveIgnoresTargetFolderFromDifferentAccount() throws IOException, InterruptedException {
    int inboxCountBefore = emailRepository.getMessageCount(accountId, inboxFolderId);
    int otherAccountFolderCountBefore =
        emailRepository.getMessageCount(otherAccountId, otherAccountFolderId);

    HttpRequest move =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select="
                        + thirdMessageId
                        + "&target_folder="
                        + otherAccountFolderId
                        + "&email_action_move=Move"))
            .build();
    HttpResponse<String> response = httpClient.send(move, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals(baseUrl + "/accounts/1", response.headers().firstValue("location").orElseThrow());

    assertEquals(inboxCountBefore, emailRepository.getMessageCount(accountId, inboxFolderId));
    assertEquals(
        otherAccountFolderCountBefore,
        emailRepository.getMessageCount(otherAccountId, otherAccountFolderId));
  }

  @Test
  @Order(7)
  void deleteMovesMessageToTrashAndRedirectsToReferer() throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(firstMessageId).isPresent());
    int trashCountBefore = emailRepository.getMessageCount(accountId, trashFolderId);

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

    assertTrue(emailRepository.findById(firstMessageId).isPresent());
    assertEquals(trashCountBefore + 1, emailRepository.getMessageCount(accountId, trashFolderId));
    assertEquals(1, queuedActionCountForType(MailboxActionType.DELETE));
  }

  @Test
  @Order(8)
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

    assertTrue(emailRepository.findById(secondMessageId).isPresent());
  }

  @Test
  @Order(2)
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

  @Test
  @Order(3)
  void archiveMovesMessageToArchiveFolderAndRedirectsToReferer()
      throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(firstMessageId).isPresent());
    int inboxCountBefore = emailRepository.getMessageCount(accountId, inboxFolderId);

    HttpRequest archive =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/1")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + firstMessageId + "&email_action_archive=Archive"))
            .build();
    HttpResponse<String> response = httpClient.send(archive, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals(baseUrl + "/accounts/1", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(firstMessageId).isPresent());
    assertEquals(inboxCountBefore - 1, emailRepository.getMessageCount(accountId, inboxFolderId));
    assertTrue(queuedActionCountForType(MailboxActionType.ARCHIVE) >= 1);
  }

  @Test
  @Order(3)
  void archiveFromViewerRedirectsToHome() throws IOException, InterruptedException {
    assertTrue(emailRepository.findById(secondMessageId).isPresent());
    int inboxCountBefore = emailRepository.getMessageCount(accountId, inboxFolderId);

    HttpRequest archive =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/emails/" + secondMessageId + "/viewer")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + secondMessageId + "&email_action_archive=Archive"))
            .build();
    HttpResponse<String> response = httpClient.send(archive, HttpResponse.BodyHandlers.ofString());
    assertEquals(303, response.statusCode());
    assertEquals("/", response.headers().firstValue("location").orElseThrow());

    assertTrue(emailRepository.findById(secondMessageId).isPresent());
    assertEquals(inboxCountBefore - 1, emailRepository.getMessageCount(accountId, inboxFolderId));
  }

  @Test
  @Order(9)
  void mappedActionRedirectsToFolderSettingsWhenRequiredMappingIsMissing()
      throws IOException, InterruptedException {
    List<Actor> actors = List.of(new Actor(ActorType.SENDER, "a@b.com", Optional.of("A")));
    ZonedDateTime now = ZonedDateTime.now();
    EmailHeader header =
        new EmailHeader(
            -1, 6L, actors, "Subject 6", now, 0L, now, 0L, "Excerpt", false, false, true);
    int messageId =
        emailRepository.addMessage(
            otherAccountFolderId, new Email(header, true, "Body 6", Collections.emptyList()));

    HttpRequest delete =
        HttpRequest.newBuilder(URI.create(baseUrl + "/emails/selection"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", baseUrl + "/accounts/" + accountId)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "email_select=" + messageId + "&email_action_delete=Delete"))
            .build();
    HttpResponse<String> response = httpClient.send(delete, HttpResponse.BodyHandlers.ofString());

    assertEquals(303, response.statusCode());
    assertEquals(
        "/accounts/" + otherAccountId + "/settings/folders?required=TRASH",
        response.headers().firstValue("location").orElseThrow());
    assertTrue(emailRepository.findById(messageId).isPresent());
    assertEquals(2, queuedActionCountForType(MailboxActionType.DELETE));
  }

  private static int queuedActionCountForType(MailboxActionType actionType) {
    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT COUNT(*) FROM mailbox_action_queue WHERE action_type = ?")) {
      stmt.setString(1, actionType.name());
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
