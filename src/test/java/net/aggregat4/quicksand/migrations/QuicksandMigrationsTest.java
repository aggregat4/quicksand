package net.aggregat4.quicksand.migrations;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.Test;

class QuicksandMigrationsTest {

  private static final Set<String> EXPECTED_TABLES =
      Set.of(
          "schema_version",
          "accounts",
          "folders",
          "messages",
          "actors",
          "drafts",
          "outbound_messages",
          "attachments",
          "message_search",
          "account_folder_mappings",
          "mailbox_action_queue");

  private static final Set<String> FOLDER_COLUMNS =
      Set.of(
          "id",
          "account_id",
          "name",
          "last_seen_uid",
          "remote_name",
          "special_use",
          "uidvalidity",
          "sync_enabled",
          "mapping_status",
          "highest_modseq",
          "last_full_sync_epoch_s",
          "last_viewed_epoch_s");

  private static final Set<String> FOLDER_INDEXES =
      Set.of(
          "folders_account_name_idx",
          "folders_account_remote_name_idx",
          "folders_account_special_use_idx");

  private static final Set<String> MESSAGE_INDEXES =
      Set.of("messages_folder_paging_idx", "messages_folder_imap_uid_idx");

  private static final Set<String> MAILBOX_ACTION_QUEUE_INDEXES =
      Set.of(
          "mailbox_action_queue_status_next_attempt_idx",
          "mailbox_action_queue_account_status_idx",
          "mailbox_action_queue_message_status_idx",
          "mailbox_action_queue_source_identity_status_idx",
          "mailbox_action_queue_resolution_resolved_idx");

  @Test
  void freshDatabaseCreatesFullSchema() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);

    try (Connection con = ds.getConnection()) {
      assertEquals(2, schemaVersion(con));
      assertEquals(EXPECTED_TABLES, applicationTableNames(con));
      assertFalse(
          applicationTableNames(con).stream().anyMatch(name -> name.contains("hardened")),
          "staging table names must not remain after migration");

      assertEquals(FOLDER_COLUMNS, columns(con, "folders"));
      assertEquals(FOLDER_INDEXES, indexes(con, "folders"));
      assertEquals(MESSAGE_INDEXES, indexes(con, "messages"));
      assertEquals(MAILBOX_ACTION_QUEUE_INDEXES, indexes(con, "mailbox_action_queue"));
      assertEquals(
          Set.of("outbound_messages_status_next_attempt_idx"), indexes(con, "outbound_messages"));
      assertEquals(
          Set.of("account_folder_mappings_account_status_idx"),
          indexes(con, "account_folder_mappings"));

      assertTrue(
          columns(con, "drafts").containsAll(Set.of("remote_imap_uid", "remote_uidvalidity")));
      assertTrue(
          columns(con, "messages").containsAll(Set.of("folder_id", "imap_uid", "plain_text")));
      assertTrue(columns(con, "actors").containsAll(Set.of("message_id", "email_address")));
      assertTrue(
          columns(con, "attachments")
              .containsAll(
                  Set.of("draft_id", "message_id", "outbound_message_id", "content_hash")));
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

      assertTrue(tableSql(con, "message_search").contains("USING fts5"));
      assertTrue(!folderTableSql(con).contains("name TEXT UNIQUE"));
      assertEquals("wal", journalMode(con));
    }
  }

  @Test
  void schemaAllowsSameFolderNameAcrossAccounts() throws Exception {
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
    }
  }

  @Test
  void schemaEnforcesFolderScopedImapUidIdentity() throws Exception {
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

    assertThrows(
        SQLException.class,
        () -> insertMessage(ds, inbox.id(), 42L),
        "duplicate imap_uid in the same folder should fail");
  }

  @Test
  void schemaCascadesFolderDeleteToMessagesAndActors() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Cascade", "imap", 993, "u", "p", "smtp", 587, "u", "p"));
    var account = accountRepository.getAccounts().getFirst();
    var inbox = folderRepository.createFolder(account, "Inbox", "INBOX", null, null);
    insertMessage(ds, inbox.id(), 7L);
    insertActor(ds, 1);

    try (Connection con = ds.getConnection()) {
      assertEquals(1, countRows(con, "messages"));
      assertEquals(1, countRows(con, "actors"));
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

  private static void insertActor(DataSource ds, int messageId) throws SQLException {
    try (Connection con = ds.getConnection();
        var stmt =
            con.prepareStatement(
                "INSERT INTO actors (message_id, type, email_address) VALUES (?, 1, 'a@b.c')")) {
      stmt.setInt(1, messageId);
      stmt.executeUpdate();
    }
  }

  private static int schemaVersion(Connection con) throws SQLException {
    return DbUtil.withPreparedStmtFunction(
        con,
        "SELECT version FROM schema_version WHERE id = 42",
        stmt ->
            DbUtil.withResultSetFunction(
                stmt,
                rs -> {
                  assertTrue(rs.next());
                  return rs.getInt(1);
                }));
  }

  private static Set<String> applicationTableNames(Connection con) throws SQLException {
    Set<String> tables = new HashSet<>();
    for (String name : tableNames(con)) {
      if (!name.startsWith("message_search_")) {
        tables.add(name);
      }
    }
    return tables;
  }

  private static Set<String> tableNames(Connection con) throws SQLException {
    Set<String> tables = new HashSet<>();
    try (var stmt =
            con.prepareStatement("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')");
        var rs = stmt.executeQuery()) {
      while (rs.next()) {
        String name = rs.getString(1);
        if (!name.startsWith("sqlite_")) {
          tables.add(name);
        }
      }
    }
    return tables;
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
    return tableSql(con, "folders");
  }

  private static String tableSql(Connection con, String table) throws SQLException {
    try (var stmt =
        con.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      stmt.setString(1, table);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private static String journalMode(Connection con) throws SQLException {
    try (var stmt = con.prepareStatement("PRAGMA journal_mode");
        var rs = stmt.executeQuery()) {
      assertTrue(rs.next());
      return rs.getString(1);
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
        String name = rs.getString("name");
        if (!name.startsWith("sqlite_autoindex_")) {
          indexes.add(name);
        }
      }
    }
    return indexes;
  }
}
