package net.aggregat4.quicksand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

public class DbTestUtils {
    public static DataSource getTempSqlite() throws IOException, SQLException {
        Path tempfile = Files.createTempFile("quicksand", ".sqlite");
        tempfile.toFile().deleteOnExit();
        String dburl = "jdbc:sqlite:%s".formatted(tempfile);
        HikariConfig hkConfig = new HikariConfig();
        hkConfig.setJdbcUrl(dburl);
        return new HikariDataSource(hkConfig);
    }
}
