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
