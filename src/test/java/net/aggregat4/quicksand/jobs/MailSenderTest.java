package net.aggregat4.quicksand.jobs;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Properties;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.icegreen.greenmail.junit5.GreenMailExtension;

import io.helidon.http.HttpMediaType;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbActorRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.OutboundMessageService;

public class MailSenderTest {

    @RegisterExtension
    static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

    @Test
    void deliversQueuedOutboundMessagesViaSmtp() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);

        String senderAddress = "sender@localhost";
        String senderUsername = "sender";
        String senderPassword = "secret";
        String recipientAddress = "recipient@localhost";
        greenMail.setUser(senderAddress, senderUsername, senderPassword);
        greenMail.setUser(recipientAddress, "recipient", "secret");

        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(
                -1,
                "SMTP Test Account",
                "localhost",
                greenMail.getImap().getServerSetup().getPort(),
                senderUsername,
                senderPassword,
                "localhost",
                greenMail.getSmtp().getServerSetup().getPort(),
                senderUsername,
                senderPassword));
        int accountId = accountRepository.getAccounts().getFirst().id();

        DbDraftRepository draftRepository = new DbDraftRepository(ds);
        ZonedDateTime createdAt = ZonedDateTime.parse("2026-03-29T12:00:00+02:00[Europe/Berlin]");
        Draft draft = draftRepository.create(new Draft(
                0,
                accountId,
                DraftType.NEW,
                Optional.empty(),
                recipientAddress,
                "",
                "",
                "SMTP test subject",
                "SMTP test body",
                false,
                createdAt,
                createdAt.toEpochSecond()));

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        AttachmentService attachmentService = new AttachmentService(attachmentRepository);
        attachmentService.storeDraftAttachment(
                draft.id(),
                "smtp-note.txt",
                HttpMediaType.create("text/plain"),
                new ByteArrayInputStream("smtp attachment body".getBytes(StandardCharsets.UTF_8)));

        DbOutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
        OutboundMessageService outboundMessageService = new OutboundMessageService(
                ds,
                accountRepository,
                draftRepository,
                attachmentRepository,
                outboundMessageRepository,
                Clock.fixed(createdAt.toInstant(), ZoneId.of("Europe/Berlin")));

        var queuedMessage = outboundMessageService.queueDraftForDelivery(draft.id()).orElseThrow();

        MailSender mailSender = new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(createdAt.plusMinutes(3).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60);
        mailSender.sendNow();

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));

        var storedMessage = outboundMessageRepository.findById(queuedMessage.id()).orElseThrow();
        assertEquals(OutboundMessageStatus.SENT, storedMessage.status());
        assertEquals(1, storedMessage.attemptCount());
        assertTrue(storedMessage.lastError().isEmpty());
        assertTrue(storedMessage.nextAttemptAt().isEmpty());
        assertTrue(storedMessage.sentAt().isPresent());

        MimeMessage deliveredMessage = greenMail.getReceivedMessages()[0];
        assertEquals("SMTP test subject", deliveredMessage.getSubject());
        Object content = deliveredMessage.getContent();
        assertTrue(content instanceof Multipart);
        Multipart multipart = (Multipart) content;
        BodyPart textPart = multipart.getBodyPart(0);
        assertTrue(textPart.getContent().toString().contains("SMTP test body"));
        BodyPart attachmentPart = multipart.getBodyPart(1);
        assertEquals("smtp-note.txt", attachmentPart.getFileName());
        assertEquals("smtp attachment body", new String(attachmentPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsSentMailBackIntoTheImapMirror() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);

        String accountAddress = "roundtrip@localhost";
        String password = "secret";
        greenMail.setUser(accountAddress, accountAddress, password);

        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(
                -1,
                "Roundtrip Account",
                "localhost",
                greenMail.getImap().getServerSetup().getPort(),
                accountAddress,
                password,
                "localhost",
                greenMail.getSmtp().getServerSetup().getPort(),
                accountAddress,
                password));
        Account account = accountRepository.getAccounts().getFirst();

        DbDraftRepository draftRepository = new DbDraftRepository(ds);
        ZonedDateTime createdAt = ZonedDateTime.parse("2026-03-29T12:00:00+02:00[Europe/Berlin]");
        Draft draft = draftRepository.create(new Draft(
                0,
                account.id(),
                DraftType.NEW,
                Optional.empty(),
                accountAddress,
                "",
                "",
                "Roundtrip IMAP subject",
                "Roundtrip IMAP body",
                false,
                createdAt,
                createdAt.toEpochSecond()));

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        DbOutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
        OutboundMessageService outboundMessageService = new OutboundMessageService(
                ds,
                accountRepository,
                draftRepository,
                attachmentRepository,
                outboundMessageRepository,
                Clock.fixed(createdAt.toInstant(), ZoneId.of("Europe/Berlin")));
        outboundMessageService.queueDraftForDelivery(draft.id()).orElseThrow();

        MailSender mailSender = new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(createdAt.plusMinutes(3).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60);
        mailSender.sendNow();

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));

        DbFolderRepository folderRepository = new DbFolderRepository(ds);
        DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbActorRepository(ds));
        Session session = Session.getInstance(new Properties(), null);
        Store store = session.getStore("imap");
        store.connect("localhost", greenMail.getImap().getServerSetup().getPort(), accountAddress, password);
        ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);

        int inboxFolderId = folderRepository.getFolders(account.id()).stream()
                .filter(folder -> folder.name().equals("INBOX"))
                .findFirst()
                .orElseThrow()
                .id();
        var inboxPage = emailRepository.getMessages(
                inboxFolderId,
                10,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                PageDirection.RIGHT,
                SortOrder.DESCENDING);

        assertEquals(1, inboxPage.emails().size());
        assertEquals("Roundtrip IMAP subject", inboxPage.emails().getFirst().header().subject());
        assertEquals("Roundtrip IMAP body", emailRepository.findById(inboxPage.emails().getFirst().header().id()).orElseThrow().body().trim());
    }

    @Test
    void failedSmtpAuthenticationSchedulesRetryWithoutImmediateRedelivery() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);

        String senderAddress = "retry-auth@localhost";
        String correctPassword = "secret";
        String wrongPassword = "wrong";
        String recipientAddress = "recipient-auth@localhost";
        greenMail.setUser(senderAddress, senderAddress, correctPassword);
        greenMail.setUser(recipientAddress, recipientAddress, correctPassword);

        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(
                -1,
                "Retry Auth Account",
                "localhost",
                greenMail.getImap().getServerSetup().getPort(),
                senderAddress,
                correctPassword,
                "localhost",
                greenMail.getSmtp().getServerSetup().getPort(),
                senderAddress,
                wrongPassword));

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        DbOutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
        ZonedDateTime attemptTime = ZonedDateTime.parse("2026-03-29T13:00:00+02:00[Europe/Berlin]");
        int outboundMessageId = queueDraft(accountRepository, attachmentRepository, outboundMessageRepository, ds, attemptTime, recipientAddress, "Retry subject", "Retry body");

        MailSender mailSender = new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(attemptTime.toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60);
        mailSender.sendNow();

        var storedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(OutboundMessageStatus.QUEUED, storedMessage.status());
        assertEquals(1, storedMessage.attemptCount());
        assertTrue(storedMessage.lastError().isPresent());
        assertEquals(attemptTime.plusSeconds(60).toEpochSecond(), storedMessage.nextAttemptAt().orElseThrow().toEpochSecond());
        assertTrue(storedMessage.sentAt().isEmpty());
        assertEquals(0, greenMail.getReceivedMessages().length);

        mailSender.sendNow();

        var unchangedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(1, unchangedMessage.attemptCount());
        assertEquals(attemptTime.plusSeconds(60).toEpochSecond(), unchangedMessage.nextAttemptAt().orElseThrow().toEpochSecond());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    @Test
    void retriesAfterBackoffAndSendsOnceCredentialsAreFixed() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);

        String senderAddress = "retry-success@localhost";
        String correctPassword = "secret";
        String wrongPassword = "wrong";
        String recipientAddress = "recipient-success@localhost";
        greenMail.setUser(senderAddress, senderAddress, correctPassword);
        greenMail.setUser(recipientAddress, recipientAddress, correctPassword);

        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(
                -1,
                "Retry Success Account",
                "localhost",
                greenMail.getImap().getServerSetup().getPort(),
                senderAddress,
                correctPassword,
                "localhost",
                greenMail.getSmtp().getServerSetup().getPort(),
                senderAddress,
                wrongPassword));
        Account account = accountRepository.getAccounts().getFirst();

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        DbOutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
        ZonedDateTime firstAttemptTime = ZonedDateTime.parse("2026-03-29T14:00:00+02:00[Europe/Berlin]");
        int outboundMessageId = queueDraft(accountRepository, attachmentRepository, outboundMessageRepository, ds, firstAttemptTime, recipientAddress, "Retry success subject", "Retry success body");

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60).sendNow();

        updateSmtpPassword(ds, account.id(), correctPassword);

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.plusSeconds(30).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60).sendNow();
        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(1, outboundMessageRepository.findById(outboundMessageId).orElseThrow().attemptCount());

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.plusSeconds(60).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                3,
                60).sendNow();

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
        var storedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(OutboundMessageStatus.SENT, storedMessage.status());
        assertEquals(2, storedMessage.attemptCount());
        assertTrue(storedMessage.lastError().isEmpty());
        assertTrue(storedMessage.nextAttemptAt().isEmpty());
        assertTrue(storedMessage.sentAt().isPresent());
        assertEquals("Retry success subject", greenMail.getReceivedMessages()[0].getSubject());
    }

    @Test
    void marksMessageFailedAfterExhaustingRetryBudget() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);

        String senderAddress = "retry-failed@localhost";
        String correctPassword = "secret";
        String wrongPassword = "wrong";
        String recipientAddress = "recipient-failed@localhost";
        greenMail.setUser(senderAddress, senderAddress, correctPassword);
        greenMail.setUser(recipientAddress, recipientAddress, correctPassword);

        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(
                -1,
                "Retry Failed Account",
                "localhost",
                greenMail.getImap().getServerSetup().getPort(),
                senderAddress,
                correctPassword,
                "localhost",
                greenMail.getSmtp().getServerSetup().getPort(),
                senderAddress,
                wrongPassword));

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        DbOutboundMessageRepository outboundMessageRepository = new DbOutboundMessageRepository(ds);
        ZonedDateTime firstAttemptTime = ZonedDateTime.parse("2026-03-29T15:00:00+02:00[Europe/Berlin]");
        int outboundMessageId = queueDraft(accountRepository, attachmentRepository, outboundMessageRepository, ds, firstAttemptTime, recipientAddress, "Retry failed subject", "Retry failed body");

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                2,
                60).sendNow();

        var retriedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(OutboundMessageStatus.QUEUED, retriedMessage.status());
        assertEquals(1, retriedMessage.attemptCount());
        assertEquals(firstAttemptTime.plusSeconds(60).toEpochSecond(), retriedMessage.nextAttemptAt().orElseThrow().toEpochSecond());

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.plusSeconds(60).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                2,
                60).sendNow();

        var failedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(OutboundMessageStatus.FAILED, failedMessage.status());
        assertEquals(2, failedMessage.attemptCount());
        assertTrue(failedMessage.lastError().isPresent());
        assertTrue(failedMessage.nextAttemptAt().isEmpty());
        assertTrue(failedMessage.sentAt().isEmpty());
        assertEquals(0, greenMail.getReceivedMessages().length);

        new MailSender(
                accountRepository,
                outboundMessageRepository,
                attachmentRepository,
                Clock.fixed(firstAttemptTime.plusSeconds(120).toInstant(), ZoneId.of("Europe/Berlin")),
                60,
                2,
                60).sendNow();

        var unchangedFailedMessage = outboundMessageRepository.findById(outboundMessageId).orElseThrow();
        assertEquals(2, unchangedFailedMessage.attemptCount());
        assertEquals(OutboundMessageStatus.FAILED, unchangedFailedMessage.status());
    }

    private static int queueDraft(
            DbAccountRepository accountRepository,
            DbAttachmentRepository attachmentRepository,
            DbOutboundMessageRepository outboundMessageRepository,
            DataSource ds,
            ZonedDateTime createdAt,
            String recipientAddress,
            String subject,
            String body) {
        Account account = accountRepository.getAccounts().getFirst();
        DbDraftRepository draftRepository = new DbDraftRepository(ds);
        Draft draft = draftRepository.create(new Draft(
                0,
                account.id(),
                DraftType.NEW,
                Optional.empty(),
                recipientAddress,
                "",
                "",
                subject,
                body,
                false,
                createdAt,
                createdAt.toEpochSecond()));
        OutboundMessageService outboundMessageService = new OutboundMessageService(
                ds,
                accountRepository,
                draftRepository,
                attachmentRepository,
                outboundMessageRepository,
                Clock.fixed(createdAt.toInstant(), ZoneId.of("Europe/Berlin")));
        return outboundMessageService.queueDraftForDelivery(draft.id()).orElseThrow().id();
    }

    private static void updateSmtpPassword(DataSource ds, int accountId, String password) throws Exception {
        try (var connection = ds.getConnection();
             var statement = connection.prepareStatement("UPDATE accounts SET smtp_password = ? WHERE id = ?")) {
            statement.setString(1, password);
            statement.setInt(2, accountId);
            statement.executeUpdate();
        }
    }
}
