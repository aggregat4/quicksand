package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import org.junit.jupiter.api.Test;

public class DbDraftRepositoryTest {

  @Test
  void createUpdateQueueAndDeleteDraft() throws SQLException, IOException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Draft Test Account",
            "imap.example.com",
            993,
            "imap-user",
            "imap-pass",
            "smtp.example.com",
            587,
            "smtp-user",
            "smtp-pass"));
    int accountId = accountRepository.getAccounts().getFirst().id();
    DbDraftRepository draftRepository = new DbDraftRepository(ds);
    ZonedDateTime createdAt = ZonedDateTime.parse("2026-03-29T12:00:00+02:00[Europe/Berlin]");

    Draft createdDraft =
        draftRepository.create(
            new Draft(
                0,
                accountId,
                DraftType.NEW,
                Optional.empty(),
                "",
                "",
                "",
                "Initial subject",
                "Initial body",
                false,
                createdAt,
                createdAt.toEpochSecond()));

    assertTrue(createdDraft.id() > 0);
    Draft storedDraft = draftRepository.findById(createdDraft.id()).orElseThrow();
    assertEquals("Initial subject", storedDraft.subject());
    assertFalse(storedDraft.queued());
    assertEquals(1, draftRepository.findOpenByAccountId(accountId).size());
    assertEquals(createdDraft.id(), draftRepository.findOpenByAccountId(accountId).getFirst().id());

    ZonedDateTime updatedAt = createdAt.plusMinutes(5);
    Draft updatedDraft =
        storedDraft
            .withContent(
                "alice@example.com",
                "bob@example.com",
                "",
                "Updated subject",
                "Updated body",
                updatedAt)
            .markQueued(updatedAt.plusMinutes(1));
    draftRepository.update(updatedDraft);

    storedDraft = draftRepository.findById(createdDraft.id()).orElseThrow();
    assertEquals("alice@example.com", storedDraft.to());
    assertEquals("bob@example.com", storedDraft.cc());
    assertEquals("Updated subject", storedDraft.subject());
    assertEquals("Updated body", storedDraft.body());
    assertTrue(storedDraft.queued());
    assertTrue(draftRepository.findOpenByAccountId(accountId).isEmpty());

    draftRepository.delete(createdDraft.id());
    assertTrue(draftRepository.findById(createdDraft.id()).isEmpty());
  }
}
