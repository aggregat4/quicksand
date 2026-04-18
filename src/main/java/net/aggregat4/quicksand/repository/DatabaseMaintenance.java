package net.aggregat4.quicksand.repository;

import java.sql.SQLException;
import javax.sql.DataSource;
import net.aggregat4.dblib.SchemaMigrator;
import net.aggregat4.quicksand.migrations.QuicksandMigrations;

public class DatabaseMaintenance {
  public static void migrateDb(DataSource ds) {
    try (var con = ds.getConnection()) {
      SchemaMigrator.migrate(con, new QuicksandMigrations());
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
