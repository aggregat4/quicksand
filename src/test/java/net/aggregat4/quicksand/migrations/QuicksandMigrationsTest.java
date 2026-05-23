package net.aggregat4.quicksand.migrations;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.Test;

class QuicksandMigrationsTest {

  @Test
  void createsImapActionSyncSchema() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);

    try (Connection con = ds.getConnection()) {
      assertTrue(
          columns(con, "folders")
              .containsAll(
                  Set.of(
                      "remote_name",
                      "special_use",
                      "uidvalidity",
                      "sync_enabled",
                      "mapping_status")));
      assertTrue(tableExists(con, "account_folder_mappings"));
      assertTrue(tableExists(con, "mailbox_action_queue"));
      assertTrue(
          columns(con, "drafts").containsAll(Set.of("remote_imap_uid", "remote_uidvalidity")));
      assertTrue(
          columns(con, "account_folder_mappings")
              .containsAll(
                  Set.of(
                      "account_id",
                      "special_use",
                      "folder_id",
                      "remote_name",
                      "status",
                      "created_at",
                      "updated_at")));
      assertTrue(
          columns(con, "mailbox_action_queue")
              .containsAll(
                  Set.of(
                      "account_id",
                      "message_id",
                      "action_type",
                      "source_folder_id",
                      "source_remote_name",
                      "source_uidvalidity",
                      "source_uid",
                      "target_folder_id",
                      "target_remote_name",
                      "target_special_use",
                      "payload_json",
                      "status",
                      "execution_state",
                      "resolution_type",
                      "attempt_count",
                      "next_attempt_at",
                      "next_attempt_at_epoch_s",
                      "last_error",
                      "created_at",
                      "updated_at",
                      "succeeded_at",
                      "resolved_at",
                      "dismissed_at",
                      "abandoned_at")));
      assertTrue(
          indexes(con, "mailbox_action_queue")
              .containsAll(
                  Set.of(
                      "mailbox_action_queue_status_next_attempt_idx",
                      "mailbox_action_queue_account_status_idx",
                      "mailbox_action_queue_message_status_idx",
                      "mailbox_action_queue_source_identity_status_idx",
                      "mailbox_action_queue_resolution_resolved_idx")));
    }
  }

  @Test
  void v8AllowsSameFolderNameAcrossAccounts() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "One", "imap", 993, "u", "p", "smtp", 587, "u", "p"));
    accountRepository.createAccountIfNew(
        new Account(-1, "Two", "imap", 993, "u", "p", "smtp", 587, "u", "p"));
    var accounts = accountRepository.getAccounts();
    folderRepository.createFolder(accounts.get(0), "Inbox", "INBOX", null, null);
    folderRepository.createFolder(accounts.get(1), "Inbox", "INBOX", null, null);

    try (Connection con = ds.getConnection()) {
      assertEquals(2, countRows(con, "folders"));
      assertTrue(indexes(con, "folders").contains("folders_account_name_idx"));
      assertTrue(!folderTableSql(con).contains("name TEXT UNIQUE"));
    }
  }

  @Test
  void v8EnforcesFolderScopedImapUidIdentity() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Uid", "imap", 993, "u", "p", "smtp", 587, "u", "p"));
    var account = accountRepository.getAccounts().getFirst();
    var inbox = folderRepository.createFolder(account, "Inbox", "INBOX", null, null);
    var archive = folderRepository.createFolder(account, "Archive", "Archive", null, null);

    insertMessage(ds, inbox.id(), 42L);
    insertMessage(ds, archive.id(), 42L);

    try (Connection con = ds.getConnection()) {
      assertEquals(2, countRows(con, "messages"));
    }

    assertThrows(
        SQLException.class,
        () -> insertMessage(ds, inbox.id(), 42L),
        "duplicate imap_uid in the same folder should fail");
  }

  @Test
  void v8CascadesFolderDeleteToMessages() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Cascade", "imap", 993, "u", "p", "smtp", 587, "u", "p"));
    var account = accountRepository.getAccounts().getFirst();
    var inbox = folderRepository.createFolder(account, "Inbox", "INBOX", null, null);
    insertMessage(ds, inbox.id(), 7L);

    try (Connection con = ds.getConnection()) {
      assertEquals(1, countRows(con, "messages"));
      execute(con, "DELETE FROM folders WHERE id = " + inbox.id());
      assertEquals(0, countRows(con, "messages"));
      assertEquals(0, countRows(con, "actors"));
    }
  }

  private static void insertMessage(DataSource ds, int folderId, long imapUid) throws SQLException {
    try (Connection con = ds.getConnection();
        var stmt =
            con.prepareStatement(
                """
                    INSERT INTO messages (
                        folder_id, imap_uid, subject, sent_date_epoch_s, received_date_epoch_s,
                        starred, read, plain_text)
                    VALUES (?, ?, 'subject', 1, 1, 0, 0, 1)
                    """)) {
      stmt.setInt(1, folderId);
      stmt.setLong(2, imapUid);
      stmt.executeUpdate();
    }
  }

  private static int countRows(Connection con, String table) throws SQLException {
    try (var stmt = con.prepareStatement("SELECT COUNT(*) FROM " + table);
        var rs = stmt.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static void execute(Connection con, String sql) throws SQLException {
    try (var stmt = con.prepareStatement(sql)) {
      stmt.executeUpdate();
    }
  }

  private static String folderTableSql(Connection con) throws SQLException {
    try (var stmt =
            con.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'folders'");
        var rs = stmt.executeQuery()) {
      assertTrue(rs.next());
      return rs.getString(1);
    }
  }

  private static boolean tableExists(Connection con, String tableName) throws SQLException {
    try (var rs =
        con.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      rs.setString(1, tableName);
      try (var result = rs.executeQuery()) {
        return result.next();
      }
    }
  }

  private static Set<String> columns(Connection con, String tableName) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (var stmt = con.prepareStatement("PRAGMA table_info(%s)".formatted(tableName));
        var rs = stmt.executeQuery()) {
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
    }
    return columns;
  }

  private static Set<String> indexes(Connection con, String tableName) throws SQLException {
    Set<String> indexes = new HashSet<>();
    try (var stmt = con.prepareStatement("PRAGMA index_list(%s)".formatted(tableName));
        var rs = stmt.executeQuery()) {
      while (rs.next()) {
        indexes.add(rs.getString("name"));
      }
    }
    return indexes;
  }
}
