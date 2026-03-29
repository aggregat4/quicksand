package net.aggregat4.quicksand.service;

import io.helidon.http.HttpMediaType;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OutboundMessageServiceTest {

    @Test
    void queueingDraftMovesAttachmentsIntoOutbox() throws Exception {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);
        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(-1, "Queued Mail Account", "imap.example.com", 993, "imap-user@example.com", "imap-pass", "smtp.example.com", 587, "smtp-user@example.com", "smtp-pass"));
        int accountId = accountRepository.getAccounts().getFirst().id();

        DbDraftRepository draftRepository = new DbDraftRepository(ds);
        ZonedDateTime createdAt = ZonedDateTime.parse("2026-03-29T12:00:00+02:00[Europe/Berlin]");
        Draft draft = draftRepository.create(new Draft(
                0,
                accountId,
                DraftType.NEW,
                Optional.empty(),
                "Alice <alice@example.com>",
                "",
                "",
                "Queued subject",
                "Queued body",
                false,
                createdAt,
                createdAt.toEpochSecond()));

        DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
        AttachmentService attachmentService = new AttachmentService(attachmentRepository);
        var attachment = attachmentService.storeDraftAttachment(
                draft.id(),
                "queued-note.txt",
                HttpMediaType.create("text/plain"),
                new ByteArrayInputStream("queued attachment body".getBytes(StandardCharsets.UTF_8)));

        OutboundMessageService outboundMessageService = new OutboundMessageService(
                ds,
                accountRepository,
                draftRepository,
                attachmentRepository,
                new DbOutboundMessageRepository(ds),
                Clock.fixed(createdAt.toInstant(), ZoneId.of("Europe/Berlin")));

        var queuedMessage = outboundMessageService.queueDraftForDelivery(draft.id()).orElseThrow();

        assertTrue(draftRepository.findById(draft.id()).isEmpty());
        assertEquals(1, outboundMessageService.getQueuedHeaders(accountId).size());
        assertEquals(OutboundMessageStatus.QUEUED, queuedMessage.status());
        assertEquals(0, queuedMessage.attemptCount());
        assertTrue(queuedMessage.lastError().isEmpty());
        assertTrue(queuedMessage.sentAt().isEmpty());

        var queuedEmail = outboundMessageService.getQueuedMessage(queuedMessage.id()).orElseThrow();
        assertEquals("Queued subject", queuedEmail.header().subject());
        assertEquals("Queued body", queuedEmail.body());
        assertEquals(1, queuedEmail.attachments().size());
        assertEquals("queued-note.txt", queuedEmail.attachments().getFirst().name());

        var storedAttachment = attachmentService.getStoredAttachment(attachment.id()).orElseThrow();
        assertTrue(storedAttachment.draftId().isEmpty());
        assertEquals(queuedMessage.id(), storedAttachment.outboundMessageId().orElseThrow());
        assertFalse(storedAttachment.messageId().isPresent());
    }
}
