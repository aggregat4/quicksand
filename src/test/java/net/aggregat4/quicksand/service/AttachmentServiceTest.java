package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Optional;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AttachmentServiceTest {

    @Test
    void storesAndDeletesDraftAttachments() throws SQLException, IOException {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);
        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(-1, "Attachment Test Account", "imap.example.com", 993, "imap-user", "imap-pass", "smtp.example.com", 587, "smtp-user", "smtp-pass"));
        int accountId = accountRepository.getAccounts().getFirst().id();
        DbDraftRepository draftRepository = new DbDraftRepository(ds);
        ZonedDateTime createdAt = ZonedDateTime.parse("2026-03-29T12:00:00+02:00[Europe/Berlin]");
        Draft draft = draftRepository.create(new Draft(
                0,
                accountId,
                DraftType.NEW,
                Optional.empty(),
                "",
                "",
                "",
                "Attachment draft",
                "Body",
                false,
                createdAt,
                createdAt.toEpochSecond()));

        AttachmentService attachmentService = new AttachmentService(new DbAttachmentRepository(ds));

        var attachment = attachmentService.storeDraftAttachment(
                draft.id(),
                "draft-note.txt",
                io.helidon.http.HttpMediaType.create("text/plain"),
                new ByteArrayInputStream("draft attachment body".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, attachmentService.getDraftAttachments(draft.id()).size());
        var storedAttachment = attachmentService.getStoredAttachment(attachment.id()).orElseThrow();
        assertEquals("draft-note.txt", storedAttachment.name());
        assertEquals("draft attachment body", new String(storedAttachment.content(), StandardCharsets.UTF_8));
        assertEquals("f79f624b06e3286e21d6306b26741f19ac7a6f075b5b697f568bdd28d76ed59a", storedAttachment.contentHash());

        attachmentService.deleteDraftAttachments(draft.id());

        assertTrue(attachmentService.getDraftAttachments(draft.id()).isEmpty());
        assertTrue(attachmentService.getStoredAttachment(attachment.id()).isEmpty());
    }
}
