package net.aggregat4.quicksand.migrations;

import net.aggregat4.dblib.Migrations;

import java.sql.Connection;
import java.util.Map;
import java.util.function.Function;

import static net.aggregat4.dblib.DbUtil.executeUpdate;

public class QuicksandMigrations implements Migrations {

    // TODO: Store password bcrypt encrypted and salted
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
                FOREIGN KEY (account_id) REFERENCES accounts(id),)""");
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
                received_date TEXT,
                body_excerpt TEXT,
                starred INTEGER,
                read INTEGER,
                FOREIGN KEY (folder_id) REFERENCES folders(id),)""");
        // TODO consider adding NOT NULL constraints
        // TODO this design does not consolidate addresses at all, we will have many duplicate addresses here as they are the raw addresses from the original message
        // I'm not even sure that this is wrong. We could build a separate address book structure that does not even need to be linked to this
        // we can probably just fulltext search the address fields anyway
        // see ActorType for possible values of actor type
        executeUpdate(con, """
                CREATE TABLE actors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER,
                type INTEGER,
                name TEXT,
                email_address TEXT,
                FOREIGN KEY (message_id) REFERENCES messages(id),)""");


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
