package net.aggregat4.quicksand.migrations;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
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
