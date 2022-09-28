package net.aggregat4.quicksand;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class SqliteTest {

    @Test
    public void createDatabaseFtsIndexAndQuery() throws SQLException, IOException {
        Path tempfile = Files.createTempFile("quicksand", ".sqlite");
        tempfile.toFile().deleteOnExit();
        String dburl = "jdbc:sqlite:%s".formatted(tempfile);
        Connection connection = DriverManager.getConnection(dburl);
        Statement statement = connection.createStatement();
        statement.executeUpdate("drop table if exists emailfts");
//        statement.executeUpdate("create table emails (id INTEGER PRIMARY KEY, subject string, body string)");
        statement.executeUpdate("CREATE VIRTUAL TABLE emailfts USING fts5(subject, body)");
        statement.executeUpdate("insert into emailfts values('I am the son of a prince of nigeria', 'I was wondering if you are interested in a proposal.')");
        statement.executeUpdate("insert into emailfts values('yo son', 'wassup? you are the man')");
        ResultSet rs = statement.executeQuery("SELECT highlight(emailfts, 0, '<b>', '</b>'), highlight(emailfts, 1, '<b>', '</b>') FROM emailfts WHERE emailfts MATCH 'yo' ORDER BY rank");
        while (rs.next()) {
            System.out.print(rs.getString(1));
            System.out.print(", ");
            System.out.println(rs.getString(2));
        }
        rs.close();
    }

}
