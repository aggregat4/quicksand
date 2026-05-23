package net.aggregat4.quicksand.migrations;

import static net.aggregat4.dblib.DbUtil.executeQuery;
import static net.aggregat4.dblib.DbUtil.executeUpdate;

import java.sql.Connection;
import java.util.Map;
import java.util.function.Function;
import net.aggregat4.dblib.Migrations;

public class QuicksandMigrations implements Migrations {

  // IMAP/SMTP passwords are stored encrypted at rest (see AccountCredentialCipher).
  // Quicksand is still pre-production, so keep the schema definition collapsed into one
  // explicit migration to make the current data model easy to read in one place.
  private static final Function<Connection, Integer> v2Migration =
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
                account_id INTEGER,
                name TEXT UNIQUE,
                last_seen_uid INTEGER,
                FOREIGN KEY (account_id) REFERENCES accounts(id))""");
        // STRING dates are ISO strings with ms precision
        // starred and read are booleans with 0 and 1 as possible values
        // TODO index on imap_uid since we do lookups there
        // TODO attachment handling
        executeUpdate(
            con,
            """
                CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER,
                imap_uid INTEGER,
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
                FOREIGN KEY (folder_id) REFERENCES folders(id))""");
        // TODO consider adding NOT NULL constraints
        // TODO this design does not consolidate addresses at all, we will have many duplicate
        // addresses here as they are the raw addresses from the original message
        // I'm not even sure that this is wrong. We could build a separate address book structure
        // that does not even need to be linked to this
        // we can probably just fulltext search the address fields anyway
        // see ActorType for possible values of actor type
        executeUpdate(
            con,
            """
                CREATE TABLE actors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER,
                type INTEGER NOT NULL,
                name TEXT,
                email_address TEXT NOT NULL,
                FOREIGN KEY (message_id) REFERENCES messages(id))""");
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
                CREATE VIRTUAL TABLE message_search USING fts5(
                subject,
                body_excerpt,
                body,
                actors,
                tokenize = 'unicode61 remove_diacritics 2')""");
        // Enable WAL mode on the database to allow for concurrent reads and writes
        executeQuery(
            con,
            """
                PRAGMA journal_mode=WAL;""");
        return 2;
      };

  private static final Function<Connection, Integer> v3Migration =
      (con) -> {
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN remote_name TEXT");
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN special_use TEXT");
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN uidvalidity INTEGER");
        executeUpdate(
            con, "ALTER TABLE folders ADD COLUMN sync_enabled INTEGER NOT NULL DEFAULT 1");
        executeUpdate(
            con, "ALTER TABLE folders ADD COLUMN mapping_status TEXT NOT NULL DEFAULT 'MISSING'");
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
        return 3;
      };

  private static final Function<Connection, Integer> v4Migration =
      (con) -> {
        executeUpdate(con, "ALTER TABLE drafts ADD COLUMN remote_imap_uid INTEGER");
        executeUpdate(con, "ALTER TABLE drafts ADD COLUMN remote_uidvalidity INTEGER");
        return 4;
      };

  private static final Function<Connection, Integer> v5Migration =
      (con) -> {
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN highest_modseq INTEGER");
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN last_full_sync_epoch_s INTEGER");
        return 5;
      };

  private static final Function<Connection, Integer> v6Migration =
      (con) -> {
        executeUpdate(con, "ALTER TABLE folders ADD COLUMN last_viewed_epoch_s INTEGER");
        return 6;
      };

  private static final Function<Connection, Integer> v7Migration =
      (con) -> {
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
        return 7;
      };

  @Override
  public Map<Integer, Function<Connection, Integer>> getMigrations() {
    return Map.of(
        2, v2Migration,
        3, v3Migration,
        4, v4Migration,
        5, v5Migration,
        6, v6Migration,
        7, v7Migration);
  }

  @Override
  public int getCurrentVersion() {
    return 7;
  }
}
