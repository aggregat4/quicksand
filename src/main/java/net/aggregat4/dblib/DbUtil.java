package net.aggregat4.dblib;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public class DbUtil {

    public static Boolean returnsRow(PreparedStatement stmt) {
        return withResultSetFunction(stmt, ResultSet::next);
    }

    public static class RuntimeSQLException extends RuntimeException {
        public RuntimeSQLException(Throwable t) {
            super(t);
        }
        public RuntimeSQLException(String message) {
            super(message);
        }
    }

    public interface SQLFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    public interface SQLConsumer<T> {
        void apply(T t) throws SQLException;
    }

    public static void withConConsumer(DataSource ds, SQLConsumer<Connection> conConsumer) {
        try (var con = ds.getConnection()) {
            conConsumer.apply(con);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    public static <T> T withConFunction(DataSource ds, SQLFunction<Connection, T> conFunction) {
        try (var con = ds.getConnection()) {
            return conFunction.apply(con);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    public static void withStmtConsumer(Connection con, SQLConsumer<Statement> stmtConsumer) {
        try (var stmt = con.createStatement()) {
            stmtConsumer.apply(stmt);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    public static void withPreparedStmtConsumer(DataSource ds, String sql, SQLConsumer<PreparedStatement> stmtConsumer) {
        withConConsumer(ds, con -> withPreparedStmtConsumer(con, sql, stmtConsumer));
    }

    public static void withPreparedStmtConsumer(Connection con, String sql, SQLConsumer<PreparedStatement> stmtConsumer) {
        try (var stmt = con.prepareStatement(sql)) {
            stmtConsumer.apply(stmt);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    public static <T> T withPreparedStmtFunction(DataSource ds, String sql, SQLFunction<PreparedStatement, T> stmtFunction) {
        return withConFunction(ds, con -> withPreparedStmtFunction(con, sql, stmtFunction));
    }

    public static <T> T withPreparedStmtFunction(Connection con, String sql, SQLFunction<PreparedStatement, T> stmtFunction) {
        try (var stmt = con.prepareStatement(sql)) {
            return stmtFunction.apply(stmt);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }


    public static void withResultSetConsumer(PreparedStatement stmt, SQLConsumer<ResultSet> rsFunction) {
        try (var rs = stmt.executeQuery()) {
            rsFunction.apply(rs);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }
    public static <T> T withResultSetFunction(PreparedStatement stmt, SQLFunction<ResultSet, T> rsFunction) {
        try (var rs = stmt.executeQuery()) {
            return rsFunction.apply(rs);
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    public static void executeUpdate(Connection con, String sql) {
        withStmtConsumer(con, (stmt) -> stmt.executeUpdate(sql));
    }

    /**
     * Warning: there could be a table with the same name in another schema, I think, and therefore return
     * false positives.
     * <p>
     * TODO make this more robust with schema identifier? Is this available in all databases?
     */
    public static boolean tableExists(Connection con, String tableName) {
        try (var rs = con.getMetaData().getTables(null, con.getSchema(), tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }


    public static <T> Optional<T> getValue(PreparedStatement stmt, SQLFunction<ResultSet, T> extractor) {
        try (var rs = stmt.executeQuery()) {
            return rs.next() ? Optional.of(extractor.apply(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeSQLException(e);
        }
    }

    // This is from https://stackoverflow.com/a/1915197/1996
    public static int getGeneratedKey(PreparedStatement stmt) {
        try (var keysRs =  stmt.getGeneratedKeys()) {
            if (keysRs.next()) {
                return keysRs.getInt(1);
            } else {
                throw new IllegalStateException("Could not retrieve the generated id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not retrieve the generated id");
        }
    }


    public static void executeUpdateAndVerify(PreparedStatement stmt) throws SQLException {
        int affectedRows = stmt.executeUpdate();
        if (affectedRows == 0) {
            throw new IllegalStateException("Modification operation affected 0 rows");
        }
    }

}
