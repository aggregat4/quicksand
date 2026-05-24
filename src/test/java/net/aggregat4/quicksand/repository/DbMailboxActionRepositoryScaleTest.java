package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.junit.jupiter.api.Test;

class DbMailboxActionRepositoryScaleTest {

  private static final int FOLDER_MESSAGE_COUNT = 10_000;
  private static final long QUERY_BUDGET_NANOS = 500_000_000L;
  private static final long LOOKUP_BUDGET_NANOS = 50_000_000L;

  @Test
  void pendingReadStateLookupScalesToThousands() throws Exception {
    int[] pendingCounts = {2_000, 5_000, 10_000};
    for (int pendingCount : pendingCounts) {
      ScaleFixture fixture = createFixture(pendingCount);
      DbMailboxActionRepository repository = new DbMailboxActionRepository(fixture.dataSource());

      long queryStart = System.nanoTime();
      Set<Long> pendingReadStateSourceUids =
          repository.getPendingReadStateActionSourceUids(
              fixture.account().id(), fixture.inbox().remoteName(), fixture.inbox().uidValidity());
      long queryNanos = System.nanoTime() - queryStart;

      assertEquals(pendingCount, pendingReadStateSourceUids.size());

      long lookupStart = System.nanoTime();
      int skippedReadSync = 0;
      for (long uid = 1; uid <= FOLDER_MESSAGE_COUNT; uid++) {
        if (pendingReadStateSourceUids.contains(uid)) {
          skippedReadSync++;
        }
      }
      long lookupNanos = System.nanoTime() - lookupStart;

      assertEquals(pendingCount, skippedReadSync);
      assertTrue(
          queryNanos < QUERY_BUDGET_NANOS,
          () ->
              "pending=%d query took %.2f ms (budget %.2f ms)"
                  .formatted(
                      pendingCount, queryNanos / 1_000_000.0, QUERY_BUDGET_NANOS / 1_000_000.0));
      assertTrue(
          lookupNanos < LOOKUP_BUDGET_NANOS,
          () ->
              "pending=%d hash lookups took %.2f ms (budget %.2f ms)"
                  .formatted(
                      pendingCount, lookupNanos / 1_000_000.0, LOOKUP_BUDGET_NANOS / 1_000_000.0));

      System.out.printf(
          "pendingReadStateLookup pending=%d queryMs=%.2f lookupMs=%.2f folderMessages=%d%n",
          pendingCount, queryNanos / 1_000_000.0, lookupNanos / 1_000_000.0, FOLDER_MESSAGE_COUNT);
    }
  }

  @Test
  void pendingReadStateLookupIgnoresUnrelatedQueueRows() throws Exception {
    ScaleFixture fixture = createFixture(2_000);
    seedPendingMoveLikeActions(fixture.dataSource(), fixture, 3_000, 10_000L);

    DbMailboxActionRepository repository = new DbMailboxActionRepository(fixture.dataSource());

    long queryStart = System.nanoTime();
    Set<Long> pendingReadStateSourceUids =
        repository.getPendingReadStateActionSourceUids(
            fixture.account().id(), fixture.inbox().remoteName(), fixture.inbox().uidValidity());
    long queryNanos = System.nanoTime() - queryStart;

    assertEquals(2_000, pendingReadStateSourceUids.size());
    assertTrue(queryNanos < QUERY_BUDGET_NANOS);

    System.out.printf(
        "pendingReadStateLookup mixedQueue readPending=%d movePending=%d queryMs=%.2f%n",
        2_000, 3_000, queryNanos / 1_000_000.0);
  }

  private static ScaleFixture createFixture(int pendingReadCount) throws Exception {
    DataSource dataSource = DbTestUtils.getTempSqlite();
    migrateDb(dataSource);
    DbAccountRepository accountRepository = new DbAccountRepository(dataSource);
    accountRepository.createAccountIfNew(
        new Account(-1, "Scale Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(dataSource);
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);
    seedPendingReadStateActions(dataSource, account.id(), inbox.id(), pendingReadCount);
    return new ScaleFixture(dataSource, account, inbox);
  }

  private static void seedPendingReadStateActions(
      DataSource dataSource, int accountId, int folderId, int count) throws Exception {
    try (Connection con = dataSource.getConnection()) {
      con.setAutoCommit(false);
      try (PreparedStatement stmt =
          con.prepareStatement(
              """
                  INSERT INTO mailbox_action_queue (
                    account_id, action_type, source_folder_id, source_remote_name,
                    source_uidvalidity, source_uid, status, execution_state)
                  VALUES (?, 'MARK_READ', ?, 'INBOX', 100, ?, 'PENDING', 'NOT_ATTEMPTED')""")) {
        for (int uid = 1; uid <= count; uid++) {
          stmt.setInt(1, accountId);
          stmt.setInt(2, folderId);
          stmt.setLong(3, uid);
          stmt.addBatch();
          if (uid % 500 == 0) {
            stmt.executeBatch();
          }
        }
        stmt.executeBatch();
      }
      con.commit();
    }
  }

  private static void seedPendingMoveLikeActions(
      DataSource dataSource, ScaleFixture fixture, int count, long uidOffset) throws Exception {
    try (Connection con = dataSource.getConnection()) {
      con.setAutoCommit(false);
      try (PreparedStatement stmt =
          con.prepareStatement(
              """
                  INSERT INTO mailbox_action_queue (
                    account_id, action_type, source_folder_id, source_remote_name,
                    source_uidvalidity, source_uid, target_remote_name, status, execution_state)
                  VALUES (?, 'ARCHIVE', ?, 'INBOX', 100, ?, 'Archive', 'PENDING', 'NOT_ATTEMPTED')""")) {
        for (int i = 0; i < count; i++) {
          stmt.setInt(1, fixture.account().id());
          stmt.setInt(2, fixture.inbox().id());
          stmt.setLong(3, uidOffset + i);
          stmt.addBatch();
          if (i % 500 == 499) {
            stmt.executeBatch();
          }
        }
        stmt.executeBatch();
      }
      con.commit();
    }
  }

  private record ScaleFixture(DataSource dataSource, Account account, NamedFolder inbox) {}
}
