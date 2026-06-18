package net.aggregat4.quicksand.webservice;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.DraftService;
import net.aggregat4.quicksand.service.EmailService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.junit.jupiter.api.Test;

class EmailBodyCacheControlTest {

  @Test
  void htmlEmailBodyUsesEtagAndReturnsNotModifiedWhenUnchanged() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Body Cache", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    NamedFolder inbox =
        folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));
    ZonedDateTime now =
        ZonedDateTime.ofInstant(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    int messageId =
        emailRepository.addMessage(
            inbox.id(),
            new Email(
                new EmailHeader(
                    -1,
                    1L,
                    List.of(new Actor(ActorType.SENDER, "sender@example.com", Optional.empty())),
                    "HTML cache subject",
                    now,
                    now.toEpochSecond(),
                    now,
                    now.toEpochSecond(),
                    "Excerpt",
                    false,
                    false,
                    false),
                false,
                "<html><body><p>Cached HTML body</p></body></html>",
                List.of()));

    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);
    AccountFolderMappingService mappingService =
        new AccountFolderMappingService(mappingRepository, folderRepository, accountRepository);
    EmailService emailService = new EmailService(emailRepository);
    AttachmentService attachmentService = new AttachmentService(new DbAttachmentRepository(ds));
    DraftService draftService =
        new DraftService(
            new DbDraftRepository(ds),
            emailRepository,
            attachmentService,
            5L,
            java.time.Clock.systemUTC());
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            ds,
            accountRepository,
            new DbDraftRepository(ds),
            new DbAttachmentRepository(ds),
            new DbOutboundMessageRepository(ds),
            emailRepository,
            java.time.Clock.systemUTC());

    WebServer webServer =
        WebServer.builder()
            .port(0)
            .host("127.0.0.1")
            .routing(
                HttpRouting.builder()
                    .register(
                        "/emails",
                        new EmailWebService(
                            emailService,
                            draftService,
                            attachmentService,
                            outboundMessageService,
                            mappingService)))
            .build()
            .start();
    try {
      String url =
          "http://localhost:"
              + webServer.port(WebServer.DEFAULT_SOCKET_NAME)
              + "/emails/"
              + messageId
              + "/viewer/body?showImages=false";
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> firstResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, firstResponse.statusCode());
      assertTrue(firstResponse.body().contains("Cached HTML body"));
      String etag = firstResponse.headers().firstValue("ETag").orElseThrow();
      assertTrue(
          firstResponse
              .headers()
              .firstValue("Cache-Control")
              .orElse("")
              .contains("must-revalidate"));

      HttpResponse<String> cachedResponse =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", etag).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(304, cachedResponse.statusCode());
      assertEquals("", cachedResponse.body());
    } finally {
      webServer.stop();
    }
  }
}
