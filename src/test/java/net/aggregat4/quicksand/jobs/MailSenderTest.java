package net.aggregat4.quicksand.jobs;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import io.helidon.http.HttpMediaType;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.AttachmentService;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                60);
        mailSender.sendNow();

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));

        var storedMessage = outboundMessageRepository.findById(queuedMessage.id()).orElseThrow();
        assertEquals(OutboundMessageStatus.SENT, storedMessage.status());
        assertEquals(1, storedMessage.attemptCount());
        assertTrue(storedMessage.lastError().isEmpty());
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
}
