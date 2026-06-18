package net.aggregat4.quicksand.webservice;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
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
import net.aggregat4.quicksand.service.MailboxSyncRecoveryService;
import net.aggregat4.quicksand.service.MailboxUpdateBroadcaster;
import net.aggregat4.quicksand.service.NotificationService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import net.aggregat4.quicksand.time.ApplicationClock;
import org.junit.jupiter.api.Test;

class DocumentCacheControlTest {

  private static final String DEMO_SUBJECT = "GREENMAIL-DEMO-INBOX-SUBJECT";
  private static final String REAL_SUBJECT = "REAL-IMAP-INBOX-SUBJECT";

  @Test
  void dynamicMailboxDocumentsMustNotBeStoredByTheBrowser() throws Exception {
    TestMailbox mailbox = bootstrapMailbox(DEMO_SUBJECT);
    WebServer webServer = startServer(mailbox);
    try {
      String baseUrl = "http://localhost:" + webServer.port(WebServer.DEFAULT_SOCKET_NAME);
      HttpClient client = HttpClient.newHttpClient();
      String folderUrl =
          baseUrl + "/accounts/" + mailbox.account().id() + "/folders/" + mailbox.inbox().id();

      HttpResponse<String> folderPage =
          client.send(
              HttpRequest.newBuilder(URI.create(folderUrl)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> notifications =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/accounts/"
                              + mailbox.account().id()
                              + "/notifications?folderId="
                              + mailbox.inbox().id()))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, folderPage.statusCode());
      assertTrue(folderPage.body().contains(DEMO_SUBJECT));
      assertEquals(200, notifications.statusCode());

      assertNoStore(
          folderPage,
          "folder mailbox pages must not be stored because the same URL serves different SQLite mirrors after server restarts");
      assertNoStore(
          notifications,
          "notification HTML fragments must not be stored because they patch live mailbox state");
    } finally {
      webServer.stop();
    }
  }

  @Test
  void sameMailboxUrlReflectsCurrentDatabaseAfterRestart() throws Exception {
    TestMailbox demoMailbox = bootstrapMailbox(DEMO_SUBJECT);
    WebServer demoServer = startServer(demoMailbox);
    int port;
    String folderPath;
    try {
      port = demoServer.port(WebServer.DEFAULT_SOCKET_NAME);
      folderPath =
          "/accounts/" + demoMailbox.account().id() + "/folders/" + demoMailbox.inbox().id();
    } finally {
      demoServer.stop();
    }

    TestMailbox realMailbox = bootstrapMailbox(REAL_SUBJECT);
    WebServer realServer = startServer(realMailbox, port);
    try {
      HttpClient client = HttpClient.newHttpClient();
      String folderUrl = "http://localhost:" + port + folderPath;

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(folderUrl)).GET().build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertFalse(response.body().contains(DEMO_SUBJECT));
      assertTrue(response.body().contains(REAL_SUBJECT));
      assertNoStore(
          response,
          "without no-store, browsers reuse a cached demo inbox at the same URL after switching to a real account");
    } finally {
      realServer.stop();
    }
  }

  private static void assertNoStore(HttpResponse<?> response, String because) {
    Optional<String> cacheControl = response.headers().firstValue("Cache-Control");
    assertTrue(
        cacheControl.isPresent() && cacheControl.get().toLowerCase().contains("no-store"),
        () ->
            "Expected Cache-Control: no-store but got "
                + cacheControl.orElse("<missing>")
                + ". "
                + because);
    assertNotEquals(
        Optional.of("max-age=365000000, immutable"),
        cacheControl,
        "immutable caching is only appropriate for stable attachment bytes, not mailbox HTML");
  }

  private static TestMailbox bootstrapMailbox(String subject) throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Cache Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    NamedFolder inbox =
        folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L);
    createRequiredSpecialUseFolders(account, folderRepository);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);
    AccountFolderMappingService mappingService =
        new AccountFolderMappingService(mappingRepository, folderRepository, accountRepository);
    mappingService.syncMappingsAfterFolderDiscovery(account.id());
    mappingService.confirmAutoDetectedMappings(account.id());

    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));
    ZonedDateTime now =
        ZonedDateTime.ofInstant(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    emailRepository.addMessage(
        inbox.id(),
        new Email(
            new EmailHeader(
                -1,
                1L,
                List.of(new Actor(ActorType.SENDER, "sender@example.com", Optional.empty())),
                subject,
                now,
                now.toEpochSecond(),
                now,
                now.toEpochSecond(),
                "Excerpt",
                false,
                false,
                false),
            true,
            "Body",
            List.of()));

    return new TestMailbox(
        ds, accountRepository, folderRepository, mappingRepository, account, inbox);
  }

  private static WebServer startServer(TestMailbox mailbox) throws IOException {
    return startServer(mailbox, 0);
  }

  private static WebServer startServer(TestMailbox mailbox, int port) throws IOException {
    HttpRouting.Builder routing =
        HttpRouting.builder()
            .register(
                "/accounts",
                new AccountWebService(
                    new FolderService(mailbox.folderRepository()),
                    new AccountService(mailbox.accountRepository()),
                    new AccountFolderMappingService(
                        mailbox.mappingRepository(),
                        mailbox.folderRepository(),
                        mailbox.accountRepository()),
                    new EmailService(
                        new DbEmailRepository(
                            mailbox.dataSource(),
                            new DbAttachmentRepository(mailbox.dataSource()))),
                    new DraftService(
                        new DbDraftRepository(mailbox.dataSource()),
                        new DbEmailRepository(
                            mailbox.dataSource(), new DbAttachmentRepository(mailbox.dataSource())),
                        new AttachmentService(new DbAttachmentRepository(mailbox.dataSource())),
                        5L,
                        Clock.system(ApplicationClock.zone())),
                    new OutboundMessageService(
                        mailbox.dataSource(),
                        mailbox.accountRepository(),
                        new DbDraftRepository(mailbox.dataSource()),
                        new DbAttachmentRepository(mailbox.dataSource()),
                        new DbOutboundMessageRepository(mailbox.dataSource()),
                        new DbEmailRepository(
                            mailbox.dataSource(), new DbAttachmentRepository(mailbox.dataSource())),
                        Clock.system(ApplicationClock.zone())),
                    new MailboxSyncRecoveryService(
                        new DbEmailRepository(
                            mailbox.dataSource(), new DbAttachmentRepository(mailbox.dataSource())),
                        mailbox.mappingRepository(),
                        () -> {},
                        Clock.system(ApplicationClock.zone())),
                    new NotificationService(
                        mailbox.folderRepository(),
                        new DbEmailRepository(
                            mailbox.dataSource(), new DbAttachmentRepository(mailbox.dataSource())),
                        Clock.system(ApplicationClock.zone())),
                    new MailboxUpdateBroadcaster(),
                    Clock.system(ApplicationClock.zone())));
    if (port > 0) {
      return WebServer.builder().host("127.0.0.1").port(port).routing(routing).build().start();
    }
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

  private record TestMailbox(
      DataSource dataSource,
      DbAccountRepository accountRepository,
      DbFolderRepository folderRepository,
      DbAccountFolderMappingRepository mappingRepository,
      Account account,
      NamedFolder inbox) {}
}
