package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.SchemaMigrator;
import net.aggregat4.quicksand.migrations.QuicksandMigrations;

import javax.sql.DataSource;
import java.sql.SQLException;

public class DatabaseMaintenance {
    public static void migrateDb(DataSource ds) {
        try (var con = ds.getConnection()) {
            SchemaMigrator.migrate(con, new QuicksandMigrations());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
