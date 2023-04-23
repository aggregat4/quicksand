package net.aggregat4.quicksand;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class SqliteTest {

    @Test
    public void createDatabaseFtsIndexAndQuery() throws SQLException, IOException {
        DataSource ds = DbTestUtils.getTempSqlite();
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists emailfts");
            //        statement.executeUpdate("create table emails (id INTEGER PRIMARY KEY, subject string, body string)");
            statement.executeUpdate("CREATE VIRTUAL TABLE emailfts USING fts5(subject, body)");
            statement.executeUpdate("insert into emailfts values('I am the son of a prince of nigeria', 'I was wondering if you are interested in a proposal.')");
            statement.executeUpdate("insert into emailfts values('yo son', 'wassup? you are the man')");
            try (ResultSet rs = statement.executeQuery("SELECT highlight(emailfts, 0, '<b>', '</b>'), highlight(emailfts, 1, '<b>', '</b>') FROM emailfts WHERE emailfts MATCH 'yo' ORDER BY rank")) {
                while (rs.next()) {
                    System.out.print(rs.getString(1));
                    System.out.print(", ");
                    System.out.println(rs.getString(2));
                }
            }
        }
    }

}
