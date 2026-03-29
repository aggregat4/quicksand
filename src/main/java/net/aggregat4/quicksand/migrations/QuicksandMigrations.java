package net.aggregat4.quicksand.migrations;

import net.aggregat4.dblib.Migrations;

import java.sql.Connection;
import java.util.Map;
import java.util.function.Function;

import static net.aggregat4.dblib.DbUtil.executeQuery;
import static net.aggregat4.dblib.DbUtil.executeUpdate;

public class QuicksandMigrations implements Migrations {

    // TODO: Store password bcrypt encrypted and salted
    // Quicksand is still pre-production, so keep the schema definition collapsed into one
    // explicit migration to make the current data model easy to read in one place.
    private static final Function<Connection, Integer> v2Migration = (con) -> {
        executeUpdate(con, """
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
        executeUpdate(con, """
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
        executeUpdate(con, """
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
        // TODO this design does not consolidate addresses at all, we will have many duplicate addresses here as they are the raw addresses from the original message
        // I'm not even sure that this is wrong. We could build a separate address book structure that does not even need to be linked to this
        // we can probably just fulltext search the address fields anyway
        // see ActorType for possible values of actor type
        executeUpdate(con, """
                CREATE TABLE actors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER,
                type INTEGER NOT NULL,
                name TEXT,
                email_address TEXT NOT NULL,
                FOREIGN KEY (message_id) REFERENCES messages(id))""");
        executeUpdate(con, """
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
        executeUpdate(con, """
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
                sent_at TEXT,
                sent_at_epoch_s INTEGER,
                queued_at_epoch_s INTEGER NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (source_message_id) REFERENCES messages(id))""");
        executeUpdate(con, """
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
        // Enable WAL mode on the database to allow for concurrent reads and writes
        executeQuery(con, """
                PRAGMA journal_mode=WAL;""");
        return 2;
    };

    @Override
    public Map<Integer, Function<Connection, Integer>> getMigrations() {
        return Map.of(2, v2Migration);
    }

    @Override
    public int getCurrentVersion() {
        return 2;
    }
}
