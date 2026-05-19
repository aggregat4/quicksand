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
import java.time.Clock;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import net.aggregat4.quicksand.service.AccountService;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.FolderService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.junit.jupiter.api.Test;

class AccountFolderSettingsWebServiceTest {

  @Test
  void redirectsMailboxToFolderSettingsWhenRequiredMappingsAreMissing() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Blocked", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    int inboxFolderId =
        folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L).id();
    folderRepository.createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 2L);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);

    WebServer webServer = startServer(ds, accountRepository, folderRepository, mappingRepository);
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> accountResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + account.id()))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> folderResponse =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl + "/accounts/" + account.id() + "/folders/" + inboxFolderId))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(303, accountResponse.statusCode());
      assertEquals(
          "/accounts/" + account.id() + "/settings/folders",
          accountResponse.headers().firstValue("location").orElseThrow());
      assertEquals(303, folderResponse.statusCode());
      assertEquals(
          "/accounts/" + account.id() + "/settings/folders",
          folderResponse.headers().firstValue("location").orElseThrow());
    } finally {
      webServer.stop();
    }
  }

  @Test
  void rendersMailboxWhenRequiredMappingsAreConfigured() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Configured", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L);
    createRequiredSpecialUseFolders(account, folderRepository);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);

    WebServer webServer = startServer(ds, accountRepository, folderRepository, mappingRepository);
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + account.id()))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("Configured"));
      assertTrue(response.body().contains("Inbox"));
      assertTrue(response.body().contains("Sent"));
      assertEquals(1, countOccurrences(response.body(), "title=\"Drafts\""));
    } finally {
      webServer.stop();
    }
  }

  @Test
  void rendersAndSavesFolderMappings() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Settings", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L);
    int archiveFolderId =
        folderRepository
            .createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 2L)
            .id();
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);

    WebServer webServer = startServer(ds, accountRepository, folderRepository, mappingRepository);
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> getResponse =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/accounts/" + account.id() + "/settings/folders"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, getResponse.statusCode());
      assertTrue(getResponse.body().contains("Folder mappings"));
      assertTrue(getResponse.body().contains("Archive"));
      assertTrue(getResponse.body().contains("Existing server folder"));
      assertTrue(getResponse.body().contains("Server mailbox"));
      assertTrue(getResponse.body().contains("Create server folder"));
      assertTrue(getResponse.body().contains("Create and map"));
      assertFalse(getResponse.body().contains("Server: INBOX"));

      HttpResponse<String> postResponse =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/accounts/" + account.id() + "/settings/folders"))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString("folder_ARCHIVE=" + archiveFolderId))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(303, postResponse.statusCode());
      var savedMapping =
          mappingRepository.findByAccountId(account.id()).stream()
              .filter(mapping -> mapping.specialUse() == FolderSpecialUse.ARCHIVE)
              .findFirst()
              .orElseThrow();
      assertEquals(archiveFolderId, savedMapping.folderId());
      assertEquals(FolderMappingStatus.USER_CONFIRMED, savedMapping.status());
    } finally {
      webServer.stop();
    }
  }

  @Test
  void rendersSyncStatusAndMailboxWarningForFailedActions() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Syncing", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    int inboxFolderId =
        folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L).id();
    createRequiredSpecialUseFolders(account, folderRepository);
    seedFailedMailboxAction(ds, account.id(), inboxFolderId);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);

    WebServer webServer = startServer(ds, accountRepository, folderRepository, mappingRepository);
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> syncResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + account.id() + "/sync"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> accountResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + account.id()))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, syncResponse.statusCode());
      assertTrue(syncResponse.body().contains("Sync status"));
      assertTrue(syncResponse.body().contains("Remote mailbox sync needs attention."));
      assertTrue(syncResponse.body().contains("1</strong>"));
      assertTrue(syncResponse.body().contains("Queued subject"));
      assertTrue(syncResponse.body().contains("FAILED_RETRYABLE"));
      assertTrue(syncResponse.body().contains("Temporary IMAP failure"));
      assertEquals(200, accountResponse.statusCode());
      assertTrue(accountResponse.body().contains("Sync needs attention"));
      assertTrue(accountResponse.body().contains("/accounts/" + account.id() + "/sync"));
    } finally {
      webServer.stop();
    }
  }

  private static WebServer startServer(
      DataSource ds,
      DbAccountRepository accountRepository,
      DbFolderRepository folderRepository,
      DbAccountFolderMappingRepository mappingRepository)
      throws IOException {
    DbActorRepository actorRepository = new DbActorRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, actorRepository);
    DbDraftRepository draftRepository = new DbDraftRepository(ds);
    DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
    AttachmentService attachmentService = new AttachmentService(attachmentRepository);
    AccountFolderMappingService accountFolderMappingService =
        new AccountFolderMappingService(mappingRepository, folderRepository, accountRepository);
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            ds,
            accountRepository,
            draftRepository,
            attachmentRepository,
            new DbOutboundMessageRepository(ds),
            emailRepository,
            Clock.systemDefaultZone());
    HttpRouting.Builder routing =
        HttpRouting.builder()
            .register(
                "/accounts",
                new AccountWebService(
                    new FolderService(folderRepository),
                    new AccountService(accountRepository),
                    accountFolderMappingService,
                    new EmailService(emailRepository),
                    new DraftService(
                        draftRepository,
                        emailRepository,
                        attachmentService,
                        5L,
                        Clock.systemDefaultZone()),
                    outboundMessageService,
                    Clock.systemDefaultZone()));
    return WebServer.builder().port(0).host("127.0.0.1").routing(routing).build().start();
  }

  private static void createRequiredSpecialUseFolders(
      Account account, DbFolderRepository folderRepository) {
    folderRepository.createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 2L);
    folderRepository.createFolder(account, "Trash", "Trash", FolderSpecialUse.TRASH, 3L);
    folderRepository.createFolder(account, "Junk", "Junk", FolderSpecialUse.JUNK, 4L);
    folderRepository.createFolder(account, "Sent", "Sent", FolderSpecialUse.SENT, 5L);
    folderRepository.createFolder(account, "Drafts", "Drafts", FolderSpecialUse.DRAFTS, 6L);
  }

  private static void seedFailedMailboxAction(DataSource ds, int accountId, int folderId)
      throws Exception {
    try (Connection con = ds.getConnection()) {
      int messageId;
      try (PreparedStatement stmt =
          con.prepareStatement(
              """
                  INSERT INTO messages (
                    folder_id, imap_uid, subject, sent_date, sent_date_epoch_s,
                    received_date, received_date_epoch_s, body_excerpt, starred, read, body)
                  VALUES (?, 42, 'Queued subject', '2026-03-25T09:15:00Z', 1774430100,
                    '2026-03-25T09:15:00Z', 1774430100, 'excerpt', 0, 0, 'body')
                  RETURNING id""")) {
        stmt.setInt(1, folderId);
        try (var rs = stmt.executeQuery()) {
          rs.next();
          messageId = rs.getInt(1);
        }
      }
      try (PreparedStatement stmt =
          con.prepareStatement(
              """
                  INSERT INTO mailbox_action_queue (
                    account_id, message_id, action_type,
                    source_folder_id, source_remote_name, source_uidvalidity, source_uid,
                    target_folder_id, target_remote_name, target_special_use,
                    status, execution_state, attempt_count, next_attempt_at, last_error)
                  VALUES (?, ?, ?, ?, 'INBOX', 1, 42, ?, 'Archive', ?,
                    ?, ?, 3, '2026-03-25T09:20:00Z', 'Temporary IMAP failure')""")) {
        stmt.setInt(1, accountId);
        stmt.setInt(2, messageId);
        stmt.setString(3, MailboxActionType.MOVE.name());
        stmt.setInt(4, folderId);
        stmt.setInt(5, folderId);
        stmt.setString(6, FolderSpecialUse.ARCHIVE.name());
        stmt.setString(7, MailboxActionStatus.FAILED_RETRYABLE.name());
        stmt.setString(8, MailboxActionExecutionState.ATTEMPTED_UNKNOWN.name());
        stmt.executeUpdate();
      }
    }
  }

  private static int countOccurrences(String input, String needle) {
    int count = 0;
    int index = 0;
    while ((index = input.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }
}
