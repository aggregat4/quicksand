package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;

public class DbFolderRepository implements FolderRepository {

  private static final String FOLDER_COLUMNS =
      """
          id, name, last_seen_uid, remote_name, special_use, uidvalidity,
          sync_enabled, mapping_status, highest_modseq, last_full_sync_epoch_s""";

  private final DataSource ds;

  public DbFolderRepository(DataSource ds) {
    this.ds = ds;
  }

  @Override
  public List<NamedFolder> getFolders(int accountId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT %s
            FROM folders
            WHERE account_id = ?"""
            .formatted(FOLDER_COLUMNS),
        stmt -> {
          stmt.setInt(1, accountId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<NamedFolder> folders = new ArrayList<>();
                while (rs.next()) {
                  folders.add(readFolder(rs));
                }
                return folders;
              });
        });
  }

  @Override
  public NamedFolder createFolder(
      Account account,
      String name,
      String remoteName,
      FolderSpecialUse specialUse,
      Long uidValidity) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            INSERT INTO folders (
              account_id, name, last_seen_uid, remote_name, special_use, uidvalidity)
            VALUES (?, ?, ?, ?, ?, ?)""",
        stmt -> {
          stmt.setInt(1, account.id());
          stmt.setString(2, name);
          stmt.setLong(3, 0);
          stmt.setString(4, remoteName);
          stmt.setString(5, enumName(specialUse));
          if (uidValidity == null) {
            stmt.setNull(6, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(6, uidValidity);
          }
          stmt.executeUpdate();
          return new NamedFolder(
              stmt.getGeneratedKeys().getInt(1),
              name,
              0,
              remoteName,
              specialUse,
              uidValidity,
              true,
              FolderMappingStatus.MISSING,
              null,
              null);
        });
  }

  @Override
  public NamedFolder updateRemoteMetadata(
      NamedFolder folder, String remoteName, FolderSpecialUse specialUse, Long uidValidity) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE folders SET remote_name = ?, special_use = ?, uidvalidity = ? WHERE id = ?",
        stmt -> {
          stmt.setString(1, remoteName);
          stmt.setString(2, enumName(specialUse));
          if (uidValidity == null) {
            stmt.setNull(3, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(3, uidValidity);
          }
          stmt.setInt(4, folder.id());
          stmt.executeUpdate();
        });
    return new NamedFolder(
        folder.id(),
        folder.name(),
        folder.lastSeenUid(),
        remoteName,
        specialUse,
        uidValidity,
        folder.syncEnabled(),
        folder.mappingStatus(),
        folder.highestModSeq(),
        folder.lastFullSyncEpochS());
  }

  @Override
  public NamedFolder updateSyncCheckpoint(
      NamedFolder folder, Long highestModSeq, Long lastFullSyncEpochS) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE folders
            SET highest_modseq = ?, last_full_sync_epoch_s = ?
            WHERE id = ?""",
        stmt -> {
          if (highestModSeq == null) {
            stmt.setNull(1, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(1, highestModSeq);
          }
          if (lastFullSyncEpochS == null) {
            stmt.setNull(2, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(2, lastFullSyncEpochS);
          }
          stmt.setInt(3, folder.id());
          stmt.executeUpdate();
        });
    return folder.withSyncCheckpoint(highestModSeq, lastFullSyncEpochS);
  }

  @Override
  public void updateMappingStatus(NamedFolder folder, FolderMappingStatus mappingStatus) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE folders SET mapping_status = ? WHERE id = ?",
        stmt -> {
          stmt.setString(1, mappingStatus.name());
          stmt.setInt(2, folder.id());
          stmt.executeUpdate();
        });
  }

  /** Clears dependent rows that do not cascade before removing a mirrored folder row. */
  @Override
  public void deleteFolder(NamedFolder folder) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          int folderId = folder.id();
          detachMailboxActionsFromFolderMessages(con, folderId);
          nullifyDraftSourcesForFolderMessages(con, folderId);
          nullifyOutboundSourcesForFolderMessages(con, folderId);
          deleteSearchRowsForFolderMessages(con, folderId);
          deleteMessagesInFolder(con, folderId);
          detachMailboxActionsFromFolder(con, folderId);
          detachFolderMappings(con, folderId);
          deleteFolderRow(con, folderId);
        });
  }

  private static void detachMailboxActionsFromFolderMessages(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE mailbox_action_queue SET message_id = NULL
                WHERE message_id IN (SELECT id FROM messages WHERE folder_id = ?)""")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  private static void nullifyDraftSourcesForFolderMessages(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE drafts
                SET source_message_id = NULL
                WHERE source_message_id IN (SELECT id FROM messages WHERE folder_id = ?)""")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  private static void nullifyOutboundSourcesForFolderMessages(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE outbound_messages
                SET source_message_id = NULL
                WHERE source_message_id IN (SELECT id FROM messages WHERE folder_id = ?)""")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  private static void deleteSearchRowsForFolderMessages(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                DELETE FROM message_search
                WHERE rowid IN (SELECT id FROM messages WHERE folder_id = ?)""")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  private static void deleteMessagesInFolder(Connection con, int folderId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("DELETE FROM messages WHERE folder_id = ?")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  private static void detachMailboxActionsFromFolder(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE mailbox_action_queue
                SET source_folder_id = CASE WHEN source_folder_id = ? THEN NULL ELSE source_folder_id END,
                    target_folder_id = CASE WHEN target_folder_id = ? THEN NULL ELSE target_folder_id END
                WHERE source_folder_id = ? OR target_folder_id = ?""")) {
      stmt.setInt(1, folderId);
      stmt.setInt(2, folderId);
      stmt.setInt(3, folderId);
      stmt.setInt(4, folderId);
      stmt.executeUpdate();
    }
  }

  private static void detachFolderMappings(Connection con, int folderId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE account_folder_mappings
                SET folder_id = NULL,
                    status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE folder_id = ?""")) {
      stmt.setString(1, FolderMappingStatus.MISSING.name());
      stmt.setInt(2, folderId);
      stmt.executeUpdate();
    }
  }

  private static void deleteFolderRow(Connection con, int folderId) throws SQLException {
    try (PreparedStatement stmt = con.prepareStatement("DELETE FROM folders WHERE id = ?")) {
      stmt.setInt(1, folderId);
      stmt.executeUpdate();
    }
  }

  @Override
  public NamedFolder getFolder(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT %s
            FROM folders
            WHERE id = ?"""
            .formatted(FOLDER_COLUMNS),
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (rs.next()) {
                  return readFolder(rs);
                } else {
                  throw new IllegalStateException("No folder with id " + folderId);
                }
              });
        });
  }

  @Override
  public void markFolderViewed(int folderId, long viewedAtEpochS) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE folders SET last_viewed_epoch_s = ? WHERE id = ?",
        stmt -> {
          stmt.setLong(1, viewedAtEpochS);
          stmt.setInt(2, folderId);
          stmt.executeUpdate();
        });
  }

  private static NamedFolder readFolder(ResultSet rs) throws SQLException {
    return new NamedFolder(
        rs.getInt(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getString(4),
        nullableEnum(FolderSpecialUse.class, rs.getString(5)),
        getNullableLong(rs, 6),
        rs.getBoolean(7),
        FolderMappingStatus.valueOf(rs.getString(8)),
        getNullableLong(rs, 9),
        getNullableLong(rs, 10));
  }

  private static Long getNullableLong(ResultSet rs, int columnIndex) throws SQLException {
    long value = rs.getLong(columnIndex);
    return rs.wasNull() ? null : value;
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static <T extends Enum<T>> T nullableEnum(Class<T> enumClass, String value) {
    return value == null ? null : Enum.valueOf(enumClass, value);
  }
}
