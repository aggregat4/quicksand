package net.aggregat4.dblib;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

import static net.aggregat4.dblib.DbUtil.executeUpdate;
import static net.aggregat4.dblib.DbUtil.getValue;
import static net.aggregat4.dblib.DbUtil.tableExists;
import static net.aggregat4.dblib.DbUtil.withPreparedStmtFunction;

public class SchemaMigrator {

    private static final int VERSION_ID = 42;

    private static final Function<Connection, Integer> v1Migration = (con) -> {
        // the id here is just so we can find the one row easily and update it, it is always
        // set to SchemaMigrator.VERSION_ID
        executeUpdate(con, "CREATE TABLE schema_version (id INTEGER PRIMARY KEY, version INTEGER)");
        executeUpdate(con, "INSERT INTO schema_version VALUES (42, 1)");
        return 1;
    };


    private static void updateVersion(Connection con, int version) {
        try (var stmt = con.prepareStatement("UPDATE schema_version SET version = ? WHERE id = ?")) {
            stmt.setInt(1, version);
            stmt.setInt(2, VERSION_ID);
            int affectedRows = stmt.executeUpdate();
            if (affectedRows != 1) {
                throw new IllegalStateException("Database schema_version table could not be updated correctly, %s rows were affected in update statement".formatted(affectedRows));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    private static int determineSchemaVersion(Connection con) {
        if (tableExists(con, "schema_version")) {
            return withPreparedStmtFunction(con, "SELECT version FROM schema_version WHERE id = 42",
                    (stmt) -> getValue(stmt, (rs) -> rs.getInt(1)).orElseThrow());
        } else {
            return 0;
        }
    }

    public static void migrate(Connection con, Migrations migrations) {
        if (migrations.getCurrentVersion() <= 0) {
            throw new IllegalArgumentException("Migrations must always start at version 1");
        }
        int dbVersion = determineSchemaVersion(con);
        if (dbVersion > migrations.getCurrentVersion()) {
            throw new IllegalStateException("Database version is higher than code version: %s".formatted(dbVersion));
        }
        if (dbVersion < 0) {
            throw new IllegalStateException("Database version is below zero: %s".formatted(dbVersion));
        }
        if (dbVersion == migrations.getCurrentVersion()) {
            return;
        }
        if (dbVersion == 0) {
            dbVersion = v1Migration.apply(con);
        }
        // Make sure we always at least start at 1 (and not 0 or some negative number).
        // We increment the current dbVersion to get the startVersion for the migration because
        // they are always identified by their own version.
        int startVersion = Math.max(dbVersion + 1, 1);
        for (int i = startVersion; i <= migrations.getCurrentVersion(); i++) {
            var migration = migrations.getMigrations().get(i);
            if (migration == null) {
                throw new IllegalStateException("Do not have a migration for version %s of the schema".formatted(i));
            }
            int newVersion = migration.apply(con);
            updateVersion(con, newVersion);
        }
    }
}
