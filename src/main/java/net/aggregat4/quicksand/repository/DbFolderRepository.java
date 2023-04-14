package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.NamedFolder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

public class DbFolderRepository implements FolderRepository {

    private final DataSource ds;

    public DbFolderRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public List<NamedFolder> getFolders(int accountId) {
        return DbUtil.withPreparedStmtFunction(
                ds,
                "SELECT * FROM folders WHERE account_id = ?",
                stmt -> {
                    stmt.setInt(1, accountId);
                    return DbUtil.withResultSetFunction(stmt, rs -> {
                        List<NamedFolder> folders = new ArrayList<>();
                        while (rs.next()) {
                            folders.add(new NamedFolder(rs.getInt(1), rs.getString(2), rs.getLong(3)));
                        }
                        return folders;
                    });
                });
    }

    @Override
    public NamedFolder createFolder(Account account, String name) {
        return DbUtil.withPreparedStmtFunction(
                ds,
                "INSERT INTO folders (account_id, name) VALUES (?, ?)",
                stmt -> {
                    stmt.setInt(1, account.id());
                    stmt.setString(2, name);
                    stmt.setLong(3, 0);
                    stmt.executeUpdate();
                    return new NamedFolder(stmt.getGeneratedKeys().getInt(1), name, 0);
                });
    }

    /**
     * TODO: verify that we are cascade deleting all messages and associated things
     */
    @Override
    public void deleteFolder(NamedFolder folder) {
        DbUtil.withPreparedStmtConsumer(
                ds,
                "DELETE FROM folders WHERE id = ?",
                stmt -> {
                    stmt.setInt(1, folder.id());
                    stmt.executeUpdate();
                });
    }

    @Override
    public NamedFolder getFolder(int folderId) {
        return DbUtil.withPreparedStmtFunction(
                ds,
                "SELECT id, name, last_seen_uid FROM folders WHERE id = ?",
                stmt -> {
                    stmt.setInt(1, folderId);
                    return DbUtil.withResultSetFunction(stmt, rs -> {
                        if (rs.next()) {
                            return new NamedFolder(rs.getInt(1), rs.getString(2), rs.getLong(3));
                        } else {
                            throw new IllegalStateException("No folder with id " + folderId);
                        }
                    });
                });
    }
}
