package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;

public class DbMailboxActionRepository implements MailboxActionRepository {
  private final DataSource ds;

  public DbMailboxActionRepository(DataSource ds) {
    this.ds = ds;
  }

  private static String moveLikeReimportSuppressionSql() {
    return """
        (
          status IN (%s)
          OR (status = ? AND resolution_type IS NULL)
        )"""
        .formatted(
            EnumSql.inClause(
                MailboxActionStatus.PENDING,
                MailboxActionStatus.APPLYING,
                MailboxActionStatus.FAILED_RETRYABLE));
  }

  @Override
  public Set<Long> getPendingMoveLikeActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT source_uid
            FROM mailbox_action_queue
            WHERE account_id = ?
              AND source_remote_name = ?
              AND source_uidvalidity IS ?
              AND action_type IN (%s)
              AND %s"""
            .formatted(
                EnumSql.inClause(MailboxActionType.MOVE_LIKE), moveLikeReimportSuppressionSql()),
        stmt -> {
          stmt.setInt(1, accountId);
          stmt.setString(2, sourceRemoteName);
          if (sourceUidValidity == null) {
            stmt.setNull(3, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(3, sourceUidValidity);
          }
          stmt.setString(4, MailboxActionStatus.SUCCEEDED.name());
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                HashSet<Long> sourceUids = new HashSet<>();
                while (rs.next()) {
                  sourceUids.add(rs.getLong(1));
                }
                return sourceUids;
              });
        });
  }

  @Override
  public Set<Long> getPendingMoveLikeTargetUids(int targetFolderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT m.imap_uid
            FROM mailbox_action_queue q
            INNER JOIN messages m ON m.id = q.message_id
            WHERE q.target_folder_id = ?
              AND q.action_type IN (%s)
              AND q.status IN (%s)"""
            .formatted(
                EnumSql.inClause(MailboxActionType.MOVE_LIKE),
                EnumSql.inClause(
                    MailboxActionStatus.PENDING,
                    MailboxActionStatus.APPLYING,
                    MailboxActionStatus.FAILED_RETRYABLE)),
        stmt -> {
          stmt.setInt(1, targetFolderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                HashSet<Long> targetUids = new HashSet<>();
                while (rs.next()) {
                  targetUids.add(rs.getLong(1));
                }
                return targetUids;
              });
        });
  }

  @Override
  public Set<Long> getMoveLikeProtectedUidsInFolder(int folderId) {
    HashSet<Long> protectedUids = new HashSet<>(getPendingMoveLikeTargetUids(folderId));
    protectedUids.addAll(
        DbUtil.withPreparedStmtFunction(
            ds,
            """
                SELECT m.imap_uid
                FROM mailbox_action_queue q
                INNER JOIN messages m ON m.id = q.message_id
                WHERE m.folder_id = ?
                  AND q.action_type IN (%s)
                  AND %s"""
                .formatted(
                    EnumSql.inClause(MailboxActionType.MOVE_LIKE),
                    moveLikeReimportSuppressionSql()),
            stmt -> {
              stmt.setInt(1, folderId);
              stmt.setString(2, MailboxActionStatus.SUCCEEDED.name());
              return DbUtil.withResultSetFunction(
                  stmt,
                  rs -> {
                    HashSet<Long> sourceUids = new HashSet<>();
                    while (rs.next()) {
                      sourceUids.add(rs.getLong(1));
                    }
                    return sourceUids;
                  });
            }));
    return protectedUids;
  }

  @Override
  public void resolveMoveLikeSourceUidsAbsentFromRemote(
      int accountId, String sourceRemoteName, Long sourceUidValidity, Set<Long> remoteUidsPresent) {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET resolution_type = ?,
                resolved_at = ?,
                updated_at = ?
            WHERE account_id = ?
              AND source_remote_name = ?
              AND source_uidvalidity IS ?
              AND action_type IN (%s)
              AND status = ?
              AND resolution_type IS NULL
              AND source_uid IS NOT NULL
              AND source_uid NOT IN (SELECT value FROM json_each(?))"""
            .formatted(EnumSql.inClause(MailboxActionType.MOVE_LIKE)),
        stmt -> {
          stmt.setString(1, MailboxActionResolutionType.RESOLVED_REMOTE_MATCHED.name());
          stmt.setString(2, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(4, accountId);
          stmt.setString(5, sourceRemoteName);
          if (sourceUidValidity == null) {
            stmt.setNull(6, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(6, sourceUidValidity);
          }
          stmt.setString(7, MailboxActionStatus.SUCCEEDED.name());
          stmt.setString(8, toJsonUidArray(remoteUidsPresent));
          stmt.executeUpdate();
        });
  }

  @Override
  public void markMoveLikeActionsConflictForUidValidityChange(
      int accountId, int folderId, long newUidValidity, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET status = ?,
                execution_state = ?,
                last_error = ?,
                updated_at = ?,
                resolved_at = ?
            WHERE account_id = ?
              AND action_type IN (%s)
              AND (source_folder_id = ? OR target_folder_id = ?)
              AND resolution_type IS NULL
              AND (
                status IN (%s)
                OR (status = ? AND source_uidvalidity IS NOT NULL AND source_uidvalidity <> ?)
              )"""
            .formatted(
                EnumSql.inClause(MailboxActionType.MOVE_LIKE),
                EnumSql.inClause(
                    MailboxActionStatus.PENDING,
                    MailboxActionStatus.APPLYING,
                    MailboxActionStatus.FAILED_RETRYABLE)),
        stmt -> {
          stmt.setString(1, MailboxActionStatus.CONFLICT.name());
          stmt.setString(2, MailboxActionExecutionState.ATTEMPTED_UNKNOWN.name());
          stmt.setString(
              3,
              "Folder UIDVALIDITY changed to %d before queued action completed"
                  .formatted(newUidValidity));
          stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(5, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(6, accountId);
          stmt.setInt(7, folderId);
          stmt.setInt(8, folderId);
          stmt.setString(9, MailboxActionStatus.SUCCEEDED.name());
          stmt.setLong(10, newUidValidity);
          stmt.executeUpdate();
        });
  }

  private static String toJsonUidArray(Set<Long> uids) {
    if (uids.isEmpty()) {
      return "[]";
    }
    StringBuilder builder = new StringBuilder("[");
    boolean first = true;
    for (Long uid : uids) {
      if (!first) {
        builder.append(',');
      }
      builder.append(uid);
      first = false;
    }
    builder.append(']');
    return builder.toString();
  }

  @Override
  public Set<Long> getPendingReadStateActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT source_uid
            FROM mailbox_action_queue
            WHERE account_id = ?
              AND source_remote_name = ?
              AND source_uidvalidity IS ?
              AND action_type IN (%s)
              AND status IN (%s)"""
            .formatted(
                EnumSql.inClause(MailboxActionType.READ_STATE_SYNCABLE),
                EnumSql.inClause(
                    MailboxActionStatus.PENDING,
                    MailboxActionStatus.APPLYING,
                    MailboxActionStatus.FAILED_RETRYABLE)),
        stmt -> {
          stmt.setInt(1, accountId);
          stmt.setString(2, sourceRemoteName);
          if (sourceUidValidity == null) {
            stmt.setNull(3, java.sql.Types.BIGINT);
          } else {
            stmt.setLong(3, sourceUidValidity);
          }
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                HashSet<Long> sourceUids = new HashSet<>();
                while (rs.next()) {
                  sourceUids.add(rs.getLong(1));
                }
                return sourceUids;
              });
        });
  }

  @Override
  public MailboxSyncStatus getMailboxSyncStatus(int accountId) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          var counts = MailboxActionDbSupport.getMailboxSyncStatusCounts(con, accountId);
          List<MailboxActionQueueRow> actions =
              MailboxActionDbSupport.getMailboxSyncStatusRows(con, accountId);
          return new MailboxSyncStatus(
              counts.pendingCount(),
              counts.retryingCount(),
              counts.failedCount(),
              counts.conflictCount(),
              counts.needsAttention(),
              actions);
        });
  }

  @Override
  public boolean needsMailboxSyncAttention(int accountId) {
    return DbUtil.withConFunction(
        ds,
        con -> MailboxActionDbSupport.getMailboxSyncStatusCounts(con, accountId).needsAttention());
  }

  @Override
  public List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit) {
    return claimActions(con -> MailboxActionDbSupport.findDueMailboxActions(con, now, limit), now);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueReadStateActions(ZonedDateTime now, int limit) {
    return claimActions(
        con -> MailboxActionDbSupport.findDueReadStateActions(con, now, limit), now);
  }

  private List<MailboxActionQueueRow> claimActions(
      SqlFunction<List<MailboxActionQueueRow>> findActions, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            List<MailboxActionQueueRow> actions = findActions.apply(con);
            MailboxActionDbSupport.claimMailboxActions(con, now, actions);
            con.commit();
            return actions;
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  @FunctionalInterface
  private interface SqlFunction<T> {
    T apply(Connection con) throws SQLException;
  }

  @Override
  public void markMailboxActionSucceeded(int id, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET status = ?,
                execution_state = ?,
                updated_at = ?,
                succeeded_at = ?,
                last_error = NULL
            WHERE id = ?""",
        stmt -> {
          stmt.setString(1, MailboxActionStatus.SUCCEEDED.name());
          stmt.setString(2, MailboxActionExecutionState.CONFIRMED_APPLIED.name());
          stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(5, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public void markMailboxActionRetry(
      int id, String error, ZonedDateTime nextAttempt, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET status = ?,
                execution_state = ?,
                attempt_count = attempt_count + 1,
                next_attempt_at = ?,
                next_attempt_at_epoch_s = ?,
                last_error = ?,
                updated_at = ?
            WHERE id = ?""",
        stmt -> {
          stmt.setString(1, MailboxActionStatus.FAILED_RETRYABLE.name());
          stmt.setString(2, MailboxActionExecutionState.ATTEMPTED_UNKNOWN.name());
          stmt.setString(3, MailboxActionDbSupport.toIsoString(nextAttempt));
          stmt.setLong(4, nextAttempt.toEpochSecond());
          stmt.setString(5, error);
          stmt.setString(6, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(7, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public void markMailboxActionPermanentFailure(int id, String error, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET status = ?,
                execution_state = ?,
                attempt_count = attempt_count + 1,
                last_error = ?,
                updated_at = ?,
                resolved_at = ?
            WHERE id = ?""",
        stmt -> {
          stmt.setString(1, MailboxActionStatus.FAILED_PERMANENT.name());
          stmt.setString(2, MailboxActionExecutionState.ATTEMPTED_UNKNOWN.name());
          stmt.setString(3, error);
          stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(5, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(6, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public void scheduleDraftUpsert(int draftId, ZonedDateTime nextAttemptAt) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          try {
            Optional<MailboxActionDbSupport.DraftEnqueueContext> context =
                MailboxActionDbSupport.findDraftEnqueueContext(con, draftId);
            if (context.isEmpty()) {
              return;
            }
            Optional<MailboxActionDbSupport.TargetFolderContext> target =
                MailboxActionDbSupport.findMappedTargetFolderContext(
                    con, context.get().accountId(), FolderSpecialUse.DRAFTS);
            if (target.isEmpty()) {
              return;
            }
            if (MailboxActionDbSupport.coalesceDraftUpsert(con, draftId, nextAttemptAt)) {
              return;
            }
            MailboxActionDbSupport.enqueueDraftUpsertAction(
                con, context.get().accountId(), target.get(), draftId, nextAttemptAt);
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public void enqueueDraftDelete(int draftId) {
    DbUtil.withConConsumer(ds, con -> enqueueDraftDelete(con, draftId));
  }

  @Override
  public void enqueueDraftDelete(Connection con, int draftId) {
    try {
      MailboxActionDbSupport.cancelPendingDraftUpserts(con, draftId);
      Optional<MailboxActionDbSupport.DraftEnqueueContext> context =
          MailboxActionDbSupport.findDraftEnqueueContext(con, draftId);
      if (context.isEmpty()) {
        return;
      }
      if (context.get().remoteImapUid().isEmpty()) {
        return;
      }
      Optional<MailboxActionDbSupport.TargetFolderContext> target =
          MailboxActionDbSupport.findMappedTargetFolderContext(
              con, context.get().accountId(), FolderSpecialUse.DRAFTS);
      if (target.isEmpty()) {
        return;
      }
      if (MailboxActionDbSupport.hasUnresolvedDraftDelete(con, draftId)) {
        return;
      }
      MailboxActionDbSupport.enqueueDraftDeleteAction(con, context.get(), target.get(), draftId);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void enqueueAppendSent(int outboundMessageId) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          try {
            Optional<Integer> accountId =
                MailboxActionDbSupport.findOutboundMessageAccountId(con, outboundMessageId);
            if (accountId.isEmpty()) {
              return;
            }
            if (MailboxActionDbSupport.hasUnresolvedAppendSent(con, outboundMessageId)) {
              return;
            }
            Optional<MailboxActionDbSupport.TargetFolderContext> target =
                MailboxActionDbSupport.findMappedTargetFolderContext(
                    con, accountId.get(), FolderSpecialUse.SENT);
            if (target.isEmpty()) {
              return;
            }
            MailboxActionDbSupport.enqueueAppendSentAction(
                con, accountId.get(), target.get(), outboundMessageId);
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public void markMailboxActionConflict(int id, String error, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET status = ?,
                execution_state = ?,
                attempt_count = attempt_count + 1,
                last_error = ?,
                updated_at = ?,
                resolved_at = ?
            WHERE id = ?""",
        stmt -> {
          stmt.setString(1, MailboxActionStatus.CONFLICT.name());
          stmt.setString(2, MailboxActionExecutionState.ATTEMPTED_UNKNOWN.name());
          stmt.setString(3, error);
          stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(5, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(6, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public Optional<MailboxActionQueueRow> findMailboxAction(int actionId, int accountId) {
    return DbUtil.withConFunction(
        ds, con -> MailboxActionDbSupport.findMailboxAction(con, actionId, accountId));
  }

  @Override
  public boolean requestMailboxActionRetry(int actionId, int accountId, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          Optional<MailboxActionQueueRow> action =
              MailboxActionDbSupport.findMailboxAction(con, actionId, accountId);
          if (action.isEmpty() || !action.get().canRetryNow()) {
            return false;
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE mailbox_action_queue
                      SET status = ?,
                          next_attempt_at = ?,
                          next_attempt_at_epoch_s = ?,
                          updated_at = ?
                      WHERE id = ?
                        AND account_id = ?
                        AND resolution_type IS NULL
                        AND status IN (%s)"""
                      .formatted(
                          EnumSql.inClause(
                              MailboxActionStatus.FAILED_RETRYABLE,
                              MailboxActionStatus.CONFLICT,
                              MailboxActionStatus.APPLYING)))) {
            stmt.setString(1, MailboxActionStatus.PENDING.name());
            stmt.setString(2, MailboxActionDbSupport.toIsoString(now));
            stmt.setLong(3, now.toEpochSecond());
            stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
            stmt.setInt(5, actionId);
            stmt.setInt(6, accountId);
            return stmt.executeUpdate() > 0;
          }
        });
  }

  @Override
  public boolean dismissMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          Optional<MailboxActionQueueRow> action =
              MailboxActionDbSupport.findMailboxAction(con, actionId, accountId);
          if (action.isEmpty() || !action.get().canDismiss()) {
            return false;
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE mailbox_action_queue
                      SET dismissed_at = ?,
                          resolution_type = ?,
                          resolved_at = COALESCE(resolved_at, ?),
                          updated_at = ?
                      WHERE id = ?
                        AND account_id = ?
                        AND resolution_type IS NULL
                        AND status IN (%s)"""
                      .formatted(
                          EnumSql.inClause(
                              MailboxActionStatus.FAILED_PERMANENT,
                              MailboxActionStatus.CONFLICT)))) {
            stmt.setString(1, MailboxActionDbSupport.toIsoString(now));
            stmt.setString(2, MailboxActionResolutionType.DISMISSED.name());
            stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
            stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
            stmt.setInt(5, actionId);
            stmt.setInt(6, accountId);
            return stmt.executeUpdate() > 0;
          }
        });
  }

  @Override
  public boolean abandonMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          Optional<MailboxActionQueueRow> action =
              MailboxActionDbSupport.findMailboxAction(con, actionId, accountId);
          if (action.isEmpty() || !action.get().canAbandon()) {
            return false;
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE mailbox_action_queue
                      SET resolution_type = ?,
                          abandoned_at = ?,
                          resolved_at = COALESCE(resolved_at, ?),
                          updated_at = ?
                      WHERE id = ?
                        AND account_id = ?
                        AND resolution_type IS NULL
                        AND status IN (%s)"""
                      .formatted(EnumSql.inClause(MailboxActionStatus.SYNC_STATUS_VISIBLE)))) {
            stmt.setString(1, MailboxActionResolutionType.ABANDONED.name());
            stmt.setString(2, MailboxActionDbSupport.toIsoString(now));
            stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
            stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
            stmt.setInt(5, actionId);
            stmt.setInt(6, accountId);
            return stmt.executeUpdate() > 0;
          }
        });
  }

  @Override
  public boolean rollbackMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<MailboxActionQueueRow> action =
                MailboxActionDbSupport.findMailboxAction(con, actionId, accountId);
            if (action.isEmpty() || !action.get().canRollbackLocalMove()) {
              con.rollback();
              return false;
            }
            MailboxActionQueueRow row = action.get();
            MailboxActionDbSupport.moveMessageToFolder(con, row.messageId(), row.sourceFolderId());
            try (PreparedStatement stmt =
                con.prepareStatement(
                    """
                        UPDATE mailbox_action_queue
                        SET resolution_type = ?,
                            resolved_at = ?,
                            updated_at = ?
                        WHERE id = ?
                          AND account_id = ?
                          AND resolution_type IS NULL""")) {
              stmt.setString(1, MailboxActionResolutionType.ROLLED_BACK.name());
              stmt.setString(2, MailboxActionDbSupport.toIsoString(now));
              stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
              stmt.setInt(4, actionId);
              stmt.setInt(5, accountId);
              if (stmt.executeUpdate() == 0) {
                con.rollback();
                return false;
              }
            }
            con.commit();
            return true;
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  @Override
  public void resolveUnresolvedMailboxActions(
      int accountId, MailboxActionResolutionType resolutionType, ZonedDateTime now) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
            UPDATE mailbox_action_queue
            SET resolution_type = ?,
                abandoned_at = CASE
                  WHEN ? IN ('ABANDONED', 'ABANDONED_BY_RESET') THEN ?
                  ELSE abandoned_at
                END,
                resolved_at = COALESCE(resolved_at, ?),
                updated_at = ?
            WHERE account_id = ?
              AND resolution_type IS NULL
              AND status IN (%s)"""
            .formatted(EnumSql.inClause(MailboxActionStatus.UNRESOLVED)),
        stmt -> {
          stmt.setString(1, resolutionType.name());
          stmt.setString(2, resolutionType.name());
          stmt.setString(3, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(4, MailboxActionDbSupport.toIsoString(now));
          stmt.setString(5, MailboxActionDbSupport.toIsoString(now));
          stmt.setInt(6, accountId);
          stmt.executeUpdate();
        });
  }

  /**
   * Wipes all locally mirrored folders and messages for an account.
   *
   * <p>Uses the same message-eviction order as {@link DbEmailRepository#removeBatchByUid}: clear
   * non-cascading references to {@code messages(id)} before deleting FTS rows and message rows.
   */
  @Override
  public void clearMirroredMailboxState(int accountId) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      DELETE FROM mailbox_action_queue
                      WHERE message_id IN (
                        SELECT m.id
                        FROM messages m
                        JOIN folders f ON m.folder_id = f.id
                        WHERE f.account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE drafts
                      SET source_message_id = NULL
                      WHERE source_message_id IN (
                        SELECT m.id
                        FROM messages m
                        JOIN folders f ON m.folder_id = f.id
                        WHERE f.account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE outbound_messages
                      SET source_message_id = NULL
                      WHERE source_message_id IN (
                        SELECT m.id
                        FROM messages m
                        JOIN folders f ON m.folder_id = f.id
                        WHERE f.account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      DELETE FROM message_search
                      WHERE rowid IN (
                        SELECT m.id
                        FROM messages m
                        JOIN folders f ON m.folder_id = f.id
                        WHERE f.account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      DELETE FROM messages
                      WHERE folder_id IN (SELECT id FROM folders WHERE account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      DELETE FROM mailbox_action_queue
                      WHERE source_folder_id IN (SELECT id FROM folders WHERE account_id = ?)
                         OR target_folder_id IN (SELECT id FROM folders WHERE account_id = ?)""")) {
            stmt.setInt(1, accountId);
            stmt.setInt(2, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement(
                  """
                      UPDATE account_folder_mappings
                      SET folder_id = NULL,
                          status = ?,
                          updated_at = CURRENT_TIMESTAMP
                      WHERE account_id = ?
                        AND folder_id IS NOT NULL""")) {
            stmt.setString(1, FolderMappingStatus.MISSING.name());
            stmt.setInt(2, accountId);
            stmt.executeUpdate();
          }
          try (PreparedStatement stmt =
              con.prepareStatement("DELETE FROM folders WHERE account_id = ?")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
          }
        });
  }

  @Override
  public int purgeStaleMailboxActionRows(ZonedDateTime now) {
    ZonedDateTime succeededCutoff = now.minusDays(30);
    ZonedDateTime resolvedCutoff = now.minusDays(90);
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            DELETE FROM mailbox_action_queue
            WHERE (status = ?
                     AND succeeded_at IS NOT NULL
                     AND succeeded_at < ?)
               OR (resolution_type IS NOT NULL
                     AND resolved_at IS NOT NULL
                     AND resolved_at < ?)""",
        stmt -> {
          stmt.setString(1, MailboxActionStatus.SUCCEEDED.name());
          stmt.setString(2, MailboxActionDbSupport.toIsoString(succeededCutoff));
          stmt.setString(3, MailboxActionDbSupport.toIsoString(resolvedCutoff));
          return stmt.executeUpdate();
        });
  }
}
