package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.*;

public class DbDraftRepositoryTest {

    @Test
    void createUpdateQueueAndDeleteDraft() throws SQLException, IOException {
        DataSource ds = DbTestUtils.getTempSqlite();
        migrateDb(ds);
        DbAccountRepository accountRepository = new DbAccountRepository(ds);
        accountRepository.createAccountIfNew(new Account(-1, "Draft Test Account", "imap.example.com", 993, "imap-user", "imap-pass", "smtp.example.com", 587, "smtp-user", "smtp-pass"));
        int accountId = accountRepository.getAccounts().getFirst().id();
        DbDraftRepository draftRepository = new DbDraftRepository(ds);

        Draft createdDraft = draftRepository.create(new Draft(
                0,
                accountId,
                DraftType.NEW,
                Optional.empty(),
                "",
                "",
                "",
                "Initial subject",
                "Initial body",
                false));

        assertTrue(createdDraft.id() > 0);
        Draft storedDraft = draftRepository.findById(createdDraft.id()).orElseThrow();
        assertEquals("Initial subject", storedDraft.subject());
        assertFalse(storedDraft.queued());

        Draft updatedDraft = storedDraft.withContent("alice@example.com", "bob@example.com", "", "Updated subject", "Updated body").markQueued();
        draftRepository.update(updatedDraft);

        storedDraft = draftRepository.findById(createdDraft.id()).orElseThrow();
        assertEquals("alice@example.com", storedDraft.to());
        assertEquals("bob@example.com", storedDraft.cc());
        assertEquals("Updated subject", storedDraft.subject());
        assertEquals("Updated body", storedDraft.body());
        assertTrue(storedDraft.queued());

        draftRepository.delete(createdDraft.id());
        assertTrue(draftRepository.findById(createdDraft.id()).isEmpty());
    }
}
