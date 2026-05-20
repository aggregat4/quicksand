package net.aggregat4.quicksand.repository;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.AccountFolderMapping;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;

public class DbAccountFolderMappingRepository implements AccountFolderMappingRepository {

  private final DataSource ds;

  public DbAccountFolderMappingRepository(DataSource ds) {
    this.ds = ds;
  }

  @Override
  public List<AccountFolderMapping> findByAccountId(int accountId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT id, account_id, special_use, folder_id, remote_name, status
            FROM account_folder_mappings
            WHERE account_id = ?
            ORDER BY special_use""",
        stmt -> {
          stmt.setInt(1, accountId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<AccountFolderMapping> mappings = new ArrayList<>();
                while (rs.next()) {
                  int folderId = rs.getInt(4);
                  boolean folderIdWasNull = rs.wasNull();
                  mappings.add(
                      new AccountFolderMapping(
                          rs.getInt(1),
                          rs.getInt(2),
                          FolderSpecialUse.valueOf(rs.getString(3)),
                          folderIdWasNull ? null : folderId,
                          rs.getString(5),
                          FolderMappingStatus.valueOf(rs.getString(6))));
                }
                return mappings;
              });
        });
  }

  @Override
  public void save(
      int accountId,
      FolderSpecialUse specialUse,
      Integer folderId,
      String remoteName,
      FolderMappingStatus status) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            INSERT INTO account_folder_mappings (
              account_id, special_use, folder_id, remote_name, status)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(account_id, special_use) DO UPDATE SET
              folder_id = excluded.folder_id,
              remote_name = excluded.remote_name,
              status = excluded.status,
              updated_at = CURRENT_TIMESTAMP""",
        stmt -> {
          stmt.setInt(1, accountId);
          stmt.setString(2, specialUse.name());
          if (folderId == null) {
            stmt.setNull(3, java.sql.Types.INTEGER);
          } else {
            stmt.setInt(3, folderId);
          }
          stmt.setString(4, remoteName);
          stmt.setString(5, status.name());
          stmt.executeUpdate();
        });
  }

  @Override
  public void markMappedFoldersMissing(int accountId) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE account_folder_mappings
            SET folder_id = NULL,
                status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE account_id = ?
              AND folder_id IS NOT NULL""",
        stmt -> {
          stmt.setString(1, FolderMappingStatus.MISSING.name());
          stmt.setInt(2, accountId);
          stmt.executeUpdate();
        });
  }
}
