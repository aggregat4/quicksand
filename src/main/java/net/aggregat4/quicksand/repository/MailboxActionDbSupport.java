package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;

final class MailboxActionDbSupport {
  private MailboxActionDbSupport() {}

  static String toIsoString(ZonedDateTime dateTime) {
    return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  static Optional<Integer> findMessageAccountId(Connection con, int messageId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            "SELECT f.account_id FROM messages m JOIN folders f ON m.folder_id = f.id WHERE m.id = ?")) {
      stmt.setInt(1, messageId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(rs.getInt(1));
      }
    }
  }

  static Optional<Integer> findFolderAccountId(Connection con, int folderId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("SELECT account_id FROM folders WHERE id = ?")) {
      stmt.setInt(1, folderId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(rs.getInt(1));
      }
    }
  }

  static Optional<MessageActionContext> findMessageActionContext(Connection con, int messageId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT f.account_id, m.folder_id, f.remote_name, f.uidvalidity, m.imap_uid, m.read
                FROM messages m
                JOIN folders f ON m.folder_id = f.id
                WHERE m.id = ?""")) {
      stmt.setInt(1, messageId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new MessageActionContext(
                messageId,
                rs.getInt(1),
                rs.getInt(2),
                rs.getString(3),
                getNullableLong(rs, 4),
                rs.getLong(5),
                rs.getInt(6) == 1));
      }
    }
  }

  static Optional<TargetFolderContext> findMappedTargetFolderContext(
      Connection con, int accountId, FolderSpecialUse specialUse) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT folder_id, remote_name, special_use
                FROM account_folder_mappings
                WHERE account_id = ? AND special_use = ?
                  AND folder_id IS NOT NULL AND remote_name IS NOT NULL
                  AND status IN (%s)"""
                .formatted(EnumSql.inClause(FolderMappingStatus.CONFIGURED)))) {
      stmt.setInt(1, accountId);
      stmt.setString(2, specialUse.name());
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new TargetFolderContext(
                rs.getInt(1), rs.getString(2), FolderSpecialUse.valueOf(rs.getString(3))));
      }
    }
  }

  static Optional<TargetFolderContext> findTargetFolderContext(Connection con, int folderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("SELECT id, remote_name, special_use FROM folders WHERE id = ?")) {
      stmt.setInt(1, folderId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String specialUse = rs.getString(3);
        return Optional.of(
            new TargetFolderContext(
                rs.getInt(1),
                rs.getString(2),
                specialUse == null ? null : FolderSpecialUse.valueOf(specialUse)));
      }
    }
  }

  static boolean coalesceDraftUpsert(Connection con, int draftId, ZonedDateTime nextAttemptAt)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                UPDATE mailbox_action_queue
                SET status = ?,
                    next_attempt_at = ?,
                    next_attempt_at_epoch_s = ?,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE action_type = ?
                  AND payload_json = ?
                  AND status IN (%s)"""
                .formatted(EnumSql.inClause(MailboxActionStatus.CLAIMABLE)))) {
      stmt.setString(1, MailboxActionStatus.PENDING.name());
      stmt.setString(2, toIsoString(nextAttemptAt));
      stmt.setLong(3, nextAttemptAt.toEpochSecond());
      stmt.setString(4, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(5, Integer.toString(draftId));
      return stmt.executeUpdate() > 0;
    }
  }

  static void cancelPendingDraftUpserts(Connection con, int draftId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                DELETE FROM mailbox_action_queue
                WHERE action_type = ?
                  AND payload_json = ?
                  AND status IN (%s)"""
                .formatted(EnumSql.inClause(MailboxActionStatus.CLAIMABLE)))) {
      stmt.setString(1, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(2, Integer.toString(draftId));
      stmt.executeUpdate();
    }
  }

  static void enqueueDraftUpsertAction(
      Connection con,
      int accountId,
      TargetFolderContext target,
      int draftId,
      ZonedDateTime nextAttemptAt)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO mailbox_action_queue (
                  account_id, message_id, action_type,
                  target_folder_id, target_remote_name, target_special_use,
                  payload_json, next_attempt_at, next_attempt_at_epoch_s)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)""")) {
      stmt.setInt(1, accountId);
      stmt.setString(2, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setInt(3, target.folderId());
      stmt.setString(4, target.remoteName());
      stmt.setString(5, FolderSpecialUse.DRAFTS.name());
      stmt.setString(6, Integer.toString(draftId));
      stmt.setString(7, toIsoString(nextAttemptAt));
      stmt.setLong(8, nextAttemptAt.toEpochSecond());
      stmt.executeUpdate();
    }
  }

  static void enqueueDraftDeleteAction(
      Connection con, DraftEnqueueContext draft, TargetFolderContext target, int draftId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO mailbox_action_queue (
                  account_id, message_id, action_type,
                  source_remote_name, source_uidvalidity, source_uid,
                  target_folder_id, target_remote_name, target_special_use, payload_json)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
      stmt.setInt(1, draft.accountId());
      stmt.setString(2, MailboxActionType.DELETE_DRAFT.name());
      stmt.setString(3, target.remoteName());
      stmt.setLong(4, draft.remoteUidValidity().orElseThrow());
      stmt.setLong(5, draft.remoteImapUid().orElseThrow());
      stmt.setInt(6, target.folderId());
      stmt.setString(7, target.remoteName());
      stmt.setString(8, FolderSpecialUse.DRAFTS.name());
      stmt.setString(9, Integer.toString(draftId));
      stmt.executeUpdate();
    }
  }

  static boolean hasUnresolvedDraftDelete(Connection con, int draftId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT 1
                FROM mailbox_action_queue
                WHERE action_type = ?
                  AND payload_json = ?
                  AND status IN (%s)"""
                .formatted(EnumSql.inClause(MailboxActionStatus.UNRESOLVED)))) {
      stmt.setString(1, MailboxActionType.DELETE_DRAFT.name());
      stmt.setString(2, Integer.toString(draftId));
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }

  static Optional<DraftEnqueueContext> findDraftEnqueueContext(Connection con, int draftId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT account_id, remote_imap_uid, remote_uidvalidity
                FROM drafts
                WHERE id = ?""")) {
      stmt.setInt(1, draftId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new DraftEnqueueContext(
                rs.getInt(1),
                Optional.ofNullable(getNullableLong(rs, 2)),
                Optional.ofNullable(getNullableLong(rs, 3))));
      }
    }
  }

  static void enqueueAppendSentAction(
      Connection con, int accountId, TargetFolderContext target, int outboundMessageId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO mailbox_action_queue (
                  account_id, message_id, action_type,
                  target_folder_id, target_remote_name, target_special_use, payload_json)
                VALUES (?, NULL, ?, ?, ?, ?, ?)""")) {
      stmt.setInt(1, accountId);
      stmt.setString(2, MailboxActionType.APPEND_SENT.name());
      stmt.setInt(3, target.folderId());
      stmt.setString(4, target.remoteName());
      stmt.setString(5, FolderSpecialUse.SENT.name());
      stmt.setString(6, Integer.toString(outboundMessageId));
      stmt.executeUpdate();
    }
  }

  static boolean hasUnresolvedAppendSent(Connection con, int outboundMessageId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT 1
                FROM mailbox_action_queue
                WHERE action_type = ?
                  AND payload_json = ?
                  AND status IN (%s)"""
                .formatted(EnumSql.inClause(MailboxActionStatus.UNRESOLVED)))) {
      stmt.setString(1, MailboxActionType.APPEND_SENT.name());
      stmt.setString(2, Integer.toString(outboundMessageId));
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }

  static Optional<Integer> findOutboundMessageAccountId(Connection con, int outboundMessageId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("SELECT account_id FROM outbound_messages WHERE id = ?")) {
      stmt.setInt(1, outboundMessageId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(rs.getInt(1));
      }
    }
  }

  static void enqueueAction(
      Connection con,
      MessageActionContext source,
      MailboxActionType actionType,
      TargetFolderContext target,
      FolderSpecialUse targetSpecialUse,
      String payloadJson)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO mailbox_action_queue (
                  account_id, message_id, action_type,
                  source_folder_id, source_remote_name, source_uidvalidity, source_uid,
                  target_folder_id, target_remote_name, target_special_use, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
      stmt.setInt(1, source.accountId());
      stmt.setInt(2, source.messageId());
      stmt.setString(3, actionType.name());
      stmt.setInt(4, source.sourceFolderId());
      stmt.setString(5, source.sourceRemoteName());
      if (source.sourceUidValidity() == null) {
        stmt.setNull(6, java.sql.Types.BIGINT);
      } else {
        stmt.setLong(6, source.sourceUidValidity());
      }
      stmt.setLong(7, source.sourceUid());
      if (target == null) {
        stmt.setNull(8, java.sql.Types.INTEGER);
        stmt.setNull(9, java.sql.Types.VARCHAR);
      } else {
        stmt.setInt(8, target.folderId());
        stmt.setString(9, target.remoteName());
      }
      stmt.setString(10, targetSpecialUse == null ? null : targetSpecialUse.name());
      stmt.setString(11, payloadJson);
      stmt.executeUpdate();
    }
  }

  static void moveMessageToFolder(Connection con, int messageId, int targetFolderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("UPDATE messages SET folder_id = ? WHERE id = ?")) {
      stmt.setInt(1, targetFolderId);
      stmt.setInt(2, messageId);
      stmt.executeUpdate();
    }
  }

  static Long getNullableLong(ResultSet rs, int columnIndex) throws SQLException {
    long value = rs.getLong(columnIndex);
    return rs.wasNull() ? null : value;
  }

  static Integer getNullableInt(ResultSet rs, int columnIndex) throws SQLException {
    int value = rs.getInt(columnIndex);
    return rs.wasNull() ? null : value;
  }

  static MailboxSyncStatusCounts getMailboxSyncStatusCounts(Connection con, int accountId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT
                  COALESCE(SUM(CASE WHEN status IN (%s) THEN 1 ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN status = '%s' THEN 1 ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN status = '%s' THEN 1 ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN status = '%s' THEN 1 ELSE 0 END), 0),
                  COALESCE(SUM(CASE
                    WHEN status IN ('%s', '%s')
                      OR (status = '%s' AND attempt_count >= 3)
                    THEN 1 ELSE 0 END), 0)
                FROM mailbox_action_queue
                WHERE account_id = ?
                  AND dismissed_at IS NULL
                  AND resolution_type IS NULL"""
                .formatted(
                    EnumSql.inClause(MailboxActionStatus.PENDING_OR_APPLYING),
                    MailboxActionStatus.FAILED_RETRYABLE.name(),
                    MailboxActionStatus.FAILED_PERMANENT.name(),
                    MailboxActionStatus.CONFLICT.name(),
                    MailboxActionStatus.FAILED_PERMANENT.name(),
                    MailboxActionStatus.CONFLICT.name(),
                    MailboxActionStatus.FAILED_RETRYABLE.name()))) {
      stmt.setInt(1, accountId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return new MailboxSyncStatusCounts(
            rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5) > 0);
      }
    }
  }

  static List<MailboxActionQueueRow> getMailboxSyncStatusRows(Connection con, int accountId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT
                  q.id,
                  q.account_id,
                  q.message_id,
                  COALESCE(m.subject, om.subject, d.subject, ''),
                  q.action_type,
                  q.source_folder_id,
                  q.source_remote_name,
                  q.source_uidvalidity,
                  q.source_uid,
                  q.target_folder_id,
                  q.target_remote_name,
                  q.target_special_use,
                  q.status,
                  q.execution_state,
                  q.resolution_type,
                  q.attempt_count,
                  q.next_attempt_at,
                  q.last_error,
                  q.created_at,
                  q.updated_at,
                  q.payload_json
                FROM mailbox_action_queue q
                LEFT JOIN messages m ON m.id = q.message_id
                LEFT JOIN outbound_messages om
                  ON q.action_type = '%s'
                 AND om.id = CAST(q.payload_json AS INTEGER)
                LEFT JOIN drafts d
                  ON q.action_type IN ('%s', '%s')
                 AND d.id = CAST(q.payload_json AS INTEGER)
                WHERE q.account_id = ?
                """
                    .formatted(
                        MailboxActionType.APPEND_SENT.name(),
                        MailboxActionType.UPSERT_DRAFT.name(),
                        MailboxActionType.DELETE_DRAFT.name())
                + """
                  AND q.status IN (%s)
                  AND q.dismissed_at IS NULL
                  AND q.resolution_type IS NULL
                ORDER BY
                  CASE q.status
                    WHEN '%s' THEN 0
                    WHEN '%s' THEN 1
                    WHEN '%s' THEN 2
                    WHEN '%s' THEN 3
                    ELSE 4
                  END,
                  q.created_at DESC,
                  q.id DESC"""
                    .formatted(
                        EnumSql.inClause(MailboxActionStatus.SYNC_STATUS_VISIBLE),
                        MailboxActionStatus.CONFLICT.name(),
                        MailboxActionStatus.FAILED_PERMANENT.name(),
                        MailboxActionStatus.FAILED_RETRYABLE.name(),
                        MailboxActionStatus.APPLYING.name()))) {
      stmt.setInt(1, accountId);
      try (ResultSet rs = stmt.executeQuery()) {
        List<MailboxActionQueueRow> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(toMailboxActionQueueRow(rs));
        }
        return rows;
      }
    }
  }

  static List<MailboxActionQueueRow> findDueMailboxActions(
      Connection con, ZonedDateTime now, int limit) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT
                  q.id,
                  q.account_id,
                  q.message_id,
                  COALESCE(m.subject, om.subject, d.subject, ''),
                  q.action_type,
                  q.source_folder_id,
                  q.source_remote_name,
                  q.source_uidvalidity,
                  q.source_uid,
                  q.target_folder_id,
                  q.target_remote_name,
                  q.target_special_use,
                  q.status,
                  q.execution_state,
                  q.resolution_type,
                  q.attempt_count,
                  q.next_attempt_at,
                  q.last_error,
                  q.created_at,
                  q.updated_at,
                  q.payload_json
                FROM mailbox_action_queue q
                LEFT JOIN messages m ON m.id = q.message_id
                LEFT JOIN outbound_messages om
                  ON q.action_type = '%s'
                 AND om.id = CAST(q.payload_json AS INTEGER)
                LEFT JOIN drafts d
                  ON q.action_type IN ('%s', '%s')
                 AND d.id = CAST(q.payload_json AS INTEGER)
                WHERE q.status IN (%s)
                  AND q.action_type IN (%s)
                  AND (q.next_attempt_at_epoch_s IS NULL OR q.next_attempt_at_epoch_s <= ?)
                ORDER BY q.created_at, q.id
                LIMIT ?"""
                .formatted(
                    MailboxActionType.APPEND_SENT.name(),
                    MailboxActionType.UPSERT_DRAFT.name(),
                    MailboxActionType.DELETE_DRAFT.name(),
                    EnumSql.inClause(MailboxActionStatus.CLAIMABLE),
                    EnumSql.inClause(MailboxActionType.NON_READ_STATE_BACKGROUND_SYNCABLE)))) {
      stmt.setLong(1, now.toEpochSecond());
      stmt.setInt(2, limit);
      try (ResultSet rs = stmt.executeQuery()) {
        List<MailboxActionQueueRow> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(toMailboxActionQueueRow(rs));
        }
        return rows;
      }
    }
  }

  static List<MailboxActionQueueRow> findDueReadStateActions(
      Connection con, ZonedDateTime now, int limit) throws SQLException {
    Optional<ReadStateBatchKey> batchKey = findOldestDueReadStateBatchKey(con, now);
    if (batchKey.isEmpty()) {
      return List.of();
    }
    ReadStateBatchKey key = batchKey.get();
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT
                  q.id,
                  q.account_id,
                  q.message_id,
                  COALESCE(m.subject, ''),
                  q.action_type,
                  q.source_folder_id,
                  q.source_remote_name,
                  q.source_uidvalidity,
                  q.source_uid,
                  q.target_folder_id,
                  q.target_remote_name,
                  q.target_special_use,
                  q.status,
                  q.execution_state,
                  q.resolution_type,
                  q.attempt_count,
                  q.next_attempt_at,
                  q.last_error,
                  q.created_at,
                  q.updated_at,
                  q.payload_json
                FROM mailbox_action_queue q
                LEFT JOIN messages m ON m.id = q.message_id
                WHERE q.status IN (%s)
                  AND q.action_type = ?
                  AND q.account_id = ?
                  AND q.source_remote_name = ?
                  AND q.source_uidvalidity IS ?
                  AND (q.next_attempt_at_epoch_s IS NULL OR q.next_attempt_at_epoch_s <= ?)
                ORDER BY q.created_at, q.id
                LIMIT ?"""
                .formatted(EnumSql.inClause(MailboxActionStatus.CLAIMABLE)))) {
      stmt.setString(1, key.actionType().name());
      stmt.setInt(2, key.accountId());
      stmt.setString(3, key.sourceRemoteName());
      if (key.sourceUidValidity() == null) {
        stmt.setNull(4, java.sql.Types.BIGINT);
      } else {
        stmt.setLong(4, key.sourceUidValidity());
      }
      stmt.setLong(5, now.toEpochSecond());
      stmt.setInt(6, limit);
      try (ResultSet rs = stmt.executeQuery()) {
        List<MailboxActionQueueRow> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(toMailboxActionQueueRow(rs));
        }
        return rows;
      }
    }
  }

  static void claimMailboxActions(
      Connection con, ZonedDateTime now, List<MailboxActionQueueRow> actions) throws SQLException {
    for (MailboxActionQueueRow action : actions) {
      try (PreparedStatement stmt =
          con.prepareStatement(
              """
                  UPDATE mailbox_action_queue
                  SET status = ?,
                      updated_at = ?
                  WHERE id = ?
                    AND status IN (%s)"""
                  .formatted(EnumSql.inClause(MailboxActionStatus.CLAIMABLE)))) {
        stmt.setString(1, MailboxActionStatus.APPLYING.name());
        stmt.setString(2, toIsoString(now));
        stmt.setInt(3, action.id());
        stmt.executeUpdate();
      }
    }
  }

  private static Optional<ReadStateBatchKey> findOldestDueReadStateBatchKey(
      Connection con, ZonedDateTime now) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT account_id, source_remote_name, source_uidvalidity, action_type
                FROM mailbox_action_queue
                WHERE status IN (%s)
                  AND action_type IN (%s)
                  AND (next_attempt_at_epoch_s IS NULL OR next_attempt_at_epoch_s <= ?)
                ORDER BY created_at, id
                LIMIT 1"""
                .formatted(
                    EnumSql.inClause(MailboxActionStatus.CLAIMABLE),
                    EnumSql.inClause(MailboxActionType.READ_STATE_SYNCABLE)))) {
      stmt.setLong(1, now.toEpochSecond());
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new ReadStateBatchKey(
                rs.getInt(1),
                rs.getString(2),
                getNullableLong(rs, 3),
                MailboxActionType.valueOf(rs.getString(4))));
      }
    }
  }

  record ReadStateBatchKey(
      int accountId,
      String sourceRemoteName,
      Long sourceUidValidity,
      MailboxActionType actionType) {}

  static Optional<MailboxActionQueueRow> findMailboxAction(
      Connection con, int actionId, int accountId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT
                  q.id,
                  q.account_id,
                  q.message_id,
                  COALESCE(m.subject, om.subject, d.subject, ''),
                  q.action_type,
                  q.source_folder_id,
                  q.source_remote_name,
                  q.source_uidvalidity,
                  q.source_uid,
                  q.target_folder_id,
                  q.target_remote_name,
                  q.target_special_use,
                  q.status,
                  q.execution_state,
                  q.resolution_type,
                  q.attempt_count,
                  q.next_attempt_at,
                  q.last_error,
                  q.created_at,
                  q.updated_at,
                  q.payload_json
                FROM mailbox_action_queue q
                LEFT JOIN messages m ON m.id = q.message_id
                LEFT JOIN outbound_messages om
                  ON q.action_type = '%s'
                 AND om.id = CAST(q.payload_json AS INTEGER)
                LEFT JOIN drafts d
                  ON q.action_type IN ('%s', '%s')
                 AND d.id = CAST(q.payload_json AS INTEGER)
                WHERE q.id = ?
                  AND q.account_id = ?"""
                .formatted(
                    MailboxActionType.APPEND_SENT.name(),
                    MailboxActionType.UPSERT_DRAFT.name(),
                    MailboxActionType.DELETE_DRAFT.name()))) {
      stmt.setInt(1, actionId);
      stmt.setInt(2, accountId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(toMailboxActionQueueRow(rs));
      }
    }
  }

  static MailboxActionQueueRow toMailboxActionQueueRow(ResultSet rs) throws SQLException {
    return new MailboxActionQueueRow(
        rs.getInt(1),
        rs.getInt(2),
        rs.getInt(3),
        rs.getString(4),
        MailboxActionType.valueOf(rs.getString(5)),
        getNullableInt(rs, 6),
        rs.getString(7),
        getNullableLong(rs, 8),
        getNullableLong(rs, 9),
        getNullableInt(rs, 10),
        rs.getString(11),
        EnumSql.optionalEnum(FolderSpecialUse.class, rs.getString(12)),
        MailboxActionStatus.valueOf(rs.getString(13)),
        MailboxActionExecutionState.valueOf(rs.getString(14)),
        EnumSql.optionalEnum(MailboxActionResolutionType.class, rs.getString(15)),
        rs.getInt(16),
        rs.getString(17),
        rs.getString(18),
        rs.getString(19),
        rs.getString(20),
        rs.getString(21));
  }

  record MessageActionContext(
      int messageId,
      int accountId,
      int sourceFolderId,
      String sourceRemoteName,
      Long sourceUidValidity,
      long sourceUid,
      boolean read) {}

  record TargetFolderContext(int folderId, String remoteName, FolderSpecialUse specialUse) {}

  record DraftEnqueueContext(
      int accountId, Optional<Long> remoteImapUid, Optional<Long> remoteUidValidity) {}

  record MailboxSyncStatusCounts(
      int pendingCount,
      int retryingCount,
      int failedCount,
      int conflictCount,
      boolean needsAttention) {}
}
