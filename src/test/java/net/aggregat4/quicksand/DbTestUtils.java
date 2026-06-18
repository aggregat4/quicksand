package net.aggregat4.quicksand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;

public class DbTestUtils {
  public static DataSource getTempSqlite() throws IOException, SQLException {
    Path tempfile = Files.createTempFile("quicksand", ".sqlite");
    tempfile.toFile().deleteOnExit();
    SQLiteConfig sqliteConfig = new SQLiteConfig();
    sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
    sqliteConfig.setBusyTimeout(30_000);
    sqliteConfig.enforceForeignKeys(true);
    HikariConfig hkConfig = new HikariConfig();
    hkConfig.setJdbcUrl("jdbc:sqlite:%s".formatted(tempfile));
    hkConfig.setDataSourceProperties(sqliteConfig.toProperties());
    hkConfig.setMaximumPoolSize(3);
    return new HikariDataSource(hkConfig);
  }
}
