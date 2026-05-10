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
import java.time.Clock;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
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
