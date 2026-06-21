package net.aggregat4.quicksand.migrations;

import static net.aggregat4.dblib.DbUtil.executeQuery;
import static net.aggregat4.dblib.DbUtil.executeUpdate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Function;
import net.aggregat4.dblib.Migrations;
import net.aggregat4.quicksand.util.ContentHasher;

public class QuicksandMigrations implements Migrations {

  // IMAP/SMTP passwords are stored encrypted at rest (see AccountCredentialCipher).
  // Single initial schema for pre-production Quicksand; bump getCurrentVersion() when it changes.
  private static final Function<Connection, Integer> schemaMigration =
      (con) -> {
        executeUpdate(
            con,
            """
                CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE,
                imap_host TEXT,
                imap_port INTEGER,
                imap_username TEXT,
                imap_password TEXT,
                smtp_host TEXT,
                smtp_port INTEGER,
                smtp_username TEXT,
                smtp_password TEXT)""");
        executeUpdate(
            con,
            """
                CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                last_seen_uid INTEGER,
                remote_name TEXT,
                special_use TEXT,
                uidvalidity INTEGER,
                sync_enabled INTEGER NOT NULL DEFAULT 1,
                mapping_status TEXT NOT NULL DEFAULT 'MISSING',
                highest_modseq INTEGER,
                last_full_sync_epoch_s INTEGER,
                last_viewed_epoch_s INTEGER,
                FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE)""");
        executeUpdate(
            con,
            """
                CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL,
                imap_uid INTEGER NOT NULL,
                subject TEXT,
                sent_date TEXT,
                sent_date_epoch_s INTEGER,
                received_date TEXT,
                received_date_epoch_s INTEGER,
                body_excerpt TEXT,
                starred INTEGER,
                read INTEGER,
                body TEXT,
                plain_text INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE)""");
        executeUpdate(
            con,
            """
                CREATE TABLE actors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                name TEXT,
                email_address TEXT NOT NULL,
                FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE)""");
        executeUpdate(
            con,
            """
                CREATE TABLE drafts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                source_message_id INTEGER,
                to_recipients TEXT NOT NULL DEFAULT '',
                cc_recipients TEXT NOT NULL DEFAULT '',
                bcc_recipients TEXT NOT NULL DEFAULT '',
                subject TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                queued INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL,
                updated_at_epoch_s INTEGER NOT NULL,
                remote_imap_uid INTEGER,
                remote_uidvalidity INTEGER,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (source_message_id) REFERENCES messages(id))""");
        executeUpdate(
            con,
            """
                CREATE TABLE outbound_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                source_message_id INTEGER,
                from_name TEXT,
                from_address TEXT NOT NULL,
                to_recipients TEXT NOT NULL DEFAULT '',
                cc_recipients TEXT NOT NULL DEFAULT '',
                bcc_recipients TEXT NOT NULL DEFAULT '',
                subject TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'QUEUED',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                queued_at TEXT NOT NULL,
                next_attempt_at TEXT,
                next_attempt_at_epoch_s INTEGER,
                sent_at TEXT,
                sent_at_epoch_s INTEGER,
                queued_at_epoch_s INTEGER NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (source_message_id) REFERENCES messages(id))""");
        executeUpdate(
            con,
            """
                CREATE TABLE attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                draft_id INTEGER,
                message_id INTEGER,
                outbound_message_id INTEGER,
                name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                media_type TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                content BLOB NOT NULL,
                FOREIGN KEY (draft_id) REFERENCES drafts(id) ON DELETE CASCADE,
                FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
                FOREIGN KEY (outbound_message_id) REFERENCES outbound_messages(id) ON DELETE CASCADE)""");
        executeUpdate(
            con,
            """
                CREATE TABLE account_folder_mappings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                special_use TEXT NOT NULL,
                folder_id INTEGER,
                remote_name TEXT,
                status TEXT NOT NULL DEFAULT 'MISSING',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (folder_id) REFERENCES folders(id),
                UNIQUE (account_id, special_use))""");
        executeUpdate(
            con,
            """
                CREATE TABLE mailbox_action_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                message_id INTEGER,
                action_type TEXT NOT NULL,
                source_folder_id INTEGER,
                source_remote_name TEXT,
                source_uidvalidity INTEGER,
                source_uid INTEGER,
                target_folder_id INTEGER,
                target_remote_name TEXT,
                target_special_use TEXT,
                payload_json TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                execution_state TEXT NOT NULL DEFAULT 'NOT_ATTEMPTED',
                resolution_type TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TEXT,
                next_attempt_at_epoch_s INTEGER,
                last_error TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                succeeded_at TEXT,
                resolved_at TEXT,
                dismissed_at TEXT,
                abandoned_at TEXT,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (message_id) REFERENCES messages(id),
                FOREIGN KEY (source_folder_id) REFERENCES folders(id),
                FOREIGN KEY (target_folder_id) REFERENCES folders(id))""");
        executeUpdate(
            con,
            """
                CREATE VIRTUAL TABLE message_search USING fts5(
                subject,
                body_excerpt,
                body,
                actors,
                tokenize = 'unicode61 remove_diacritics 2')""");

        executeUpdate(
            con, "CREATE UNIQUE INDEX folders_account_name_idx ON folders(account_id, name)");
        executeUpdate(
            con,
            """
                CREATE UNIQUE INDEX folders_account_remote_name_idx
                ON folders(account_id, remote_name)
                WHERE remote_name IS NOT NULL""");
        executeUpdate(
            con,
            "CREATE INDEX folders_account_special_use_idx ON folders(account_id, special_use)");
        executeUpdate(
            con,
            "CREATE INDEX account_folder_mappings_account_status_idx ON account_folder_mappings(account_id, status)");
        executeUpdate(
            con,
            """
                CREATE INDEX messages_folder_paging_idx
                ON messages(folder_id, received_date_epoch_s, id)""");
        executeUpdate(
            con,
            """
                CREATE UNIQUE INDEX messages_folder_imap_uid_idx
                ON messages(folder_id, imap_uid)""");
        executeUpdate(
            con,
            """
                CREATE INDEX outbound_messages_status_next_attempt_idx
                ON outbound_messages(status, next_attempt_at_epoch_s)""");
        executeUpdate(
            con,
            "CREATE INDEX mailbox_action_queue_status_next_attempt_idx ON mailbox_action_queue(status, next_attempt_at_epoch_s)");
        executeUpdate(
            con,
            "CREATE INDEX mailbox_action_queue_account_status_idx ON mailbox_action_queue(account_id, status)");
        executeUpdate(
            con,
            "CREATE INDEX mailbox_action_queue_message_status_idx ON mailbox_action_queue(message_id, status)");
        executeUpdate(
            con,
            """
                CREATE INDEX mailbox_action_queue_source_identity_status_idx
                ON mailbox_action_queue(account_id, source_remote_name, source_uidvalidity, source_uid, status)""");
        executeUpdate(
            con,
            "CREATE INDEX mailbox_action_queue_resolution_resolved_idx ON mailbox_action_queue(resolution_type, resolved_at)");

        executeQuery(con, "PRAGMA journal_mode=WAL");
        return 2;
      };

  private static final Function<Connection, Integer> bodyContentHashMigration =
      con -> {
        executeUpdate(con, "ALTER TABLE messages ADD COLUMN body_content_hash TEXT");
        try {
          backfillBodyContentHashes(con);
        } catch (SQLException e) {
          throw new RuntimeException("Failed to backfill message body content hashes", e);
        }
        return 3;
      };

  private static void backfillBodyContentHashes(Connection con) throws SQLException {
    try (PreparedStatement select =
            con.prepareStatement("SELECT id, body FROM messages WHERE body IS NOT NULL");
        ResultSet rs = select.executeQuery();
        PreparedStatement update =
            con.prepareStatement("UPDATE messages SET body_content_hash = ? WHERE id = ?")) {
      while (rs.next()) {
        int id = rs.getInt(1);
        String body = rs.getString(2);
        update.setString(1, ContentHasher.messageBodyContentHash(body));
        update.setInt(2, id);
        update.executeUpdate();
      }
    }
  }

  private static final Function<Connection, Integer> mailboxRemoteIdentityMigration =
      con -> {
        executeUpdate(
            con,
            "ALTER TABLE messages ADD COLUMN remote_folder_id INTEGER REFERENCES folders(id) ON DELETE SET NULL");
        executeUpdate(con, "ALTER TABLE messages ADD COLUMN remote_uidvalidity INTEGER");
        executeUpdate(con, "ALTER TABLE messages ADD COLUMN remote_uid INTEGER");
        executeUpdate(con, "ALTER TABLE messages ADD COLUMN message_id_header TEXT");
        executeUpdate(
            con,
            """
                UPDATE messages
                SET remote_folder_id = folder_id,
                    remote_uid = imap_uid,
                    remote_uidvalidity = (SELECT uidvalidity FROM folders WHERE id = messages.folder_id)""");
        executeUpdate(con, "DROP INDEX messages_folder_imap_uid_idx");
        executeUpdate(
            con,
            """
                CREATE UNIQUE INDEX messages_remote_identity_idx
                ON messages(remote_folder_id, remote_uidvalidity, remote_uid)
                WHERE remote_folder_id IS NOT NULL
                  AND remote_uidvalidity IS NOT NULL
                  AND remote_uid IS NOT NULL""");
        executeUpdate(
            con, "CREATE INDEX messages_message_id_header_idx ON messages(message_id_header)");

        executeUpdate(
            con, "ALTER TABLE mailbox_action_queue ADD COLUMN target_uidvalidity INTEGER");
        executeUpdate(con, "ALTER TABLE mailbox_action_queue ADD COLUMN target_uid INTEGER");
        executeUpdate(con, "ALTER TABLE mailbox_action_queue ADD COLUMN message_id_header TEXT");
        executeUpdate(con, "ALTER TABLE mailbox_action_queue ADD COLUMN message_subject TEXT");
        executeUpdate(
            con,
            """
                UPDATE mailbox_action_queue
                SET message_id_header = (SELECT message_id_header FROM messages WHERE id = message_id),
                    message_subject = (SELECT subject FROM messages WHERE id = message_id)
                WHERE message_id IS NOT NULL""");
        return 4;
      };

  @Override
  public Map<Integer, Function<Connection, Integer>> getMigrations() {
    return Map.of(
        2, schemaMigration,
        3, bodyContentHashMigration,
        4, mailboxRemoteIdentityMigration);
  }

  @Override
  public int getCurrentVersion() {
    return 4;
  }
}
