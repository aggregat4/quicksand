package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.search.SearchQueryUtils;

public class DbEmailRepository implements EmailRepository {
  private static final int IN_BATCH_SIZE = 100;
  private static final String MESSAGE_HEADER_COLUMNS =
      """
            id, imap_uid, subject, sent_date, sent_date_epoch_s, received_date, received_date_epoch_s, body_excerpt, starred, read
            """;
  private static final String QUALIFIED_MESSAGE_HEADER_COLUMNS =
      """
            m.id, m.imap_uid, m.subject, m.sent_date, m.sent_date_epoch_s, m.received_date, m.received_date_epoch_s, m.body_excerpt, m.starred, m.read
            """;
  private static final String MESSAGE_VIEWER_COLUMNS =
      MESSAGE_HEADER_COLUMNS + ", body, plain_text";
  private final DataSource ds;

  public DbEmailRepository(DataSource ds, DbActorRepository actorRepository) {
    this.ds = ds;
  }

  @Override
  public Optional<Email> findById(int id) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT " + MESSAGE_VIEWER_COLUMNS + " FROM messages WHERE id = ?",
        stmt -> {
          stmt.setInt(1, id);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  return Optional.empty();
                }
                List<Actor> actors = getActors(id);
                return Optional.of(convertRowToViewerEmail(rs, actors));
              });
        });
  }

  @Override
  public Optional<Integer> findAccountIdByMessageId(int id) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT f.account_id FROM messages m JOIN folders f ON m.folder_id = f.id WHERE m.id = ?",
        stmt -> {
          stmt.setInt(1, id);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  return Optional.empty();
                }
                return Optional.of(rs.getInt(1));
              });
        });
  }

  @Override
  public Optional<Email> findByMessageUid(long uid) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT " + MESSAGE_HEADER_COLUMNS + " FROM messages WHERE imap_uid = ?",
        stmt -> {
          stmt.setLong(1, uid);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  return Optional.of(convertRowToListEmail(rs, actors));
                } else {
                  return Optional.empty();
                }
              });
        });
  }

  private static EmailHeader convertRowToHeader(ResultSet rs, List<Actor> actors)
      throws SQLException {
    return new EmailHeader(
        rs.getInt(1),
        rs.getLong(2),
        actors,
        rs.getString(3),
        fromISOString(rs.getString(4)),
        rs.getLong(5),
        fromISOString(rs.getString(6)),
        rs.getLong(7),
        rs.getString(8),
        rs.getInt(9) == 1,
        false,
        rs.getInt(10) == 1);
  }

  private static Email convertRowToListEmail(ResultSet rs, List<Actor> actors) throws SQLException {
    return new Email(convertRowToHeader(rs, actors), false, null, Collections.emptyList());
  }

  private static Email convertRowToViewerEmail(ResultSet rs, List<Actor> actors)
      throws SQLException {
    return new Email(
        convertRowToHeader(rs, actors),
        rs.getInt(12) == 1,
        rs.getString(11),
        Collections.emptyList());
  }

  private List<Actor> getActors(int messageId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT type, name, email_address FROM actors WHERE message_id = ?",
        stmt -> {
          stmt.setInt(1, messageId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Actor> actors = new ArrayList<>();
                while (rs.next()) {
                  actors.add(
                      new Actor(
                          ActorType.fromValue(rs.getInt(1)),
                          rs.getString(3),
                          Optional.ofNullable(rs.getString(2))));
                }
                return actors;
              });
        });
  }

  @Override
  public void updateFlags(int id, boolean messageStarred, boolean messageRead) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE messages SET starred = ?, read = ? WHERE id = ?",
        stmt -> {
          stmt.setInt(1, messageStarred ? 1 : 0);
          stmt.setInt(2, messageRead ? 1 : 0);
          stmt.setInt(3, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public void updateRead(int id, boolean messageRead) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<MessageActionContext> context = findMessageActionContext(con, id);
            if (context.isEmpty()) {
              con.rollback();
              return;
            }
            DbUtil.withPreparedStmtConsumer(
                con,
                "UPDATE messages SET read = ? WHERE id = ?",
                stmt -> {
                  stmt.setInt(1, messageRead ? 1 : 0);
                  stmt.setInt(2, id);
                  stmt.executeUpdate();
                });
            enqueueAction(
                con,
                context.get(),
                messageRead ? MailboxActionType.MARK_READ : MailboxActionType.MARK_UNREAD,
                null,
                null,
                null);
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  @Override
  public Set<Long> getAllMessageIds(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT imap_uid FROM messages WHERE folder_id = ?",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                HashSet<Long> messageIds = new HashSet<>();
                while (rs.next()) {
                  messageIds.add(rs.getLong(1));
                }
                return messageIds;
              });
        });
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
              AND status IN (%s)"""
            .formatted(
                EnumSql.inClause(MailboxActionType.MOVE_LIKE),
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
          MailboxSyncStatusCounts counts = getMailboxSyncStatusCounts(con, accountId);
          List<MailboxActionQueueRow> actions = getMailboxSyncStatusRows(con, accountId);
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
  public List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            List<MailboxActionQueueRow> actions = findDueMailboxActions(con, now, limit);
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
                stmt.setString(2, toISOString(now));
                stmt.setInt(3, action.id());
                stmt.executeUpdate();
              }
            }
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
          stmt.setString(3, toISOString(now));
          stmt.setString(4, toISOString(now));
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
          stmt.setString(3, toISOString(nextAttempt));
          stmt.setLong(4, nextAttempt.toEpochSecond());
          stmt.setString(5, error);
          stmt.setString(6, toISOString(now));
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
          stmt.setString(4, toISOString(now));
          stmt.setString(5, toISOString(now));
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
            Optional<DraftEnqueueContext> context = findDraftEnqueueContext(con, draftId);
            if (context.isEmpty()) {
              return;
            }
            Optional<TargetFolderContext> target =
                findMappedTargetFolderContext(
                    con, context.get().accountId(), FolderSpecialUse.DRAFTS);
            if (target.isEmpty()) {
              return;
            }
            if (coalesceDraftUpsert(con, draftId, nextAttemptAt)) {
              return;
            }
            enqueueDraftUpsertAction(
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
      cancelPendingDraftUpserts(con, draftId);
      Optional<DraftEnqueueContext> context = findDraftEnqueueContext(con, draftId);
      if (context.isEmpty()) {
        return;
      }
      if (context.get().remoteImapUid().isEmpty()) {
        return;
      }
      Optional<TargetFolderContext> target =
          findMappedTargetFolderContext(con, context.get().accountId(), FolderSpecialUse.DRAFTS);
      if (target.isEmpty()) {
        return;
      }
      if (hasUnresolvedDraftDelete(con, draftId)) {
        return;
      }
      enqueueDraftDeleteAction(con, context.get(), target.get(), draftId);
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
            Optional<Integer> accountId = findOutboundMessageAccountId(con, outboundMessageId);
            if (accountId.isEmpty()) {
              return;
            }
            if (hasUnresolvedAppendSent(con, outboundMessageId)) {
              return;
            }
            Optional<TargetFolderContext> target =
                findMappedTargetFolderContext(con, accountId.get(), FolderSpecialUse.SENT);
            if (target.isEmpty()) {
              return;
            }
            enqueueAppendSentAction(con, accountId.get(), target.get(), outboundMessageId);
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public void updateMessageImapUid(int messageId, long imapUid) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE messages SET imap_uid = ? WHERE id = ?",
        stmt -> {
          stmt.setLong(1, imapUid);
          stmt.setInt(2, messageId);
          stmt.executeUpdate();
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
          stmt.setString(4, toISOString(now));
          stmt.setString(5, toISOString(now));
          stmt.setInt(6, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public Optional<MailboxActionQueueRow> findMailboxAction(int actionId, int accountId) {
    return DbUtil.withConFunction(ds, con -> findMailboxAction(con, actionId, accountId));
  }

  @Override
  public boolean requestMailboxActionRetry(int actionId, int accountId, ZonedDateTime now) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          Optional<MailboxActionQueueRow> action = findMailboxAction(con, actionId, accountId);
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
            stmt.setString(2, toISOString(now));
            stmt.setLong(3, now.toEpochSecond());
            stmt.setString(4, toISOString(now));
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
          Optional<MailboxActionQueueRow> action = findMailboxAction(con, actionId, accountId);
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
            stmt.setString(1, toISOString(now));
            stmt.setString(2, MailboxActionResolutionType.DISMISSED.name());
            stmt.setString(3, toISOString(now));
            stmt.setString(4, toISOString(now));
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
          Optional<MailboxActionQueueRow> action = findMailboxAction(con, actionId, accountId);
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
            stmt.setString(2, toISOString(now));
            stmt.setString(3, toISOString(now));
            stmt.setString(4, toISOString(now));
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
            Optional<MailboxActionQueueRow> action = findMailboxAction(con, actionId, accountId);
            if (action.isEmpty() || !action.get().canRollbackLocalMove()) {
              con.rollback();
              return false;
            }
            MailboxActionQueueRow row = action.get();
            moveMessageToFolder(con, row.messageId(), row.sourceFolderId());
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
              stmt.setString(2, toISOString(now));
              stmt.setString(3, toISOString(now));
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
          stmt.setString(3, toISOString(now));
          stmt.setString(4, toISOString(now));
          stmt.setString(5, toISOString(now));
          stmt.setInt(6, accountId);
          stmt.executeUpdate();
        });
  }

  @Override
  public void clearMirroredMailboxState(int accountId) {
    DbUtil.withConConsumer(
        ds,
        con -> {
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
          stmt.setString(2, toISOString(succeededCutoff));
          stmt.setString(3, toISOString(resolvedCutoff));
          return stmt.executeUpdate();
        });
  }

  @Override
  public void removeAllByUid(Collection<Long> localMessageIds) {
    List<Long> batch = new ArrayList<>();
    for (Long messageId : localMessageIds) {
      batch.add(messageId);
      if (batch.size() >= IN_BATCH_SIZE) {
        removeBatchByUid(batch);
      }
    }
    if (!batch.isEmpty()) {
      removeBatchByUid(batch);
    }
  }

  @Override
  public int addMessage(int folderId, Email email) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            int messageId = insertMessage(con, folderId, email);
            con.commit();
            return messageId;
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  @Override
  public void addMessages(int folderId, List<Email> emails) {
    if (emails.isEmpty()) {
      return;
    }
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            for (Email email : emails) {
              insertMessage(con, folderId, email);
            }
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private int insertMessage(Connection con, int folderId, Email email) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO messages (folder_id, imap_uid, subject, sent_date, sent_date_epoch_s, received_date, received_date_epoch_s, body_excerpt, starred, read, body, plain_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            Statement.RETURN_GENERATED_KEYS)) {
      stmt.setInt(1, folderId);
      stmt.setLong(2, email.header().imapUid());
      stmt.setString(3, email.header().subject());
      stmt.setString(4, toISOString(email.header().sentDateTime()));
      stmt.setLong(5, email.header().sentDateTimeEpochSeconds());
      stmt.setString(6, toISOString(email.header().receivedDateTime()));
      stmt.setLong(7, email.header().receivedDateTimeEpochSeconds());
      stmt.setString(8, email.header().bodyExcerpt());
      stmt.setInt(9, email.header().starred() ? 1 : 0);
      stmt.setInt(10, email.header().read() ? 1 : 0);
      stmt.setString(11, email.body());
      stmt.setInt(12, email.plainText() ? 1 : 0);
      stmt.executeUpdate();
      try (ResultSet keyRs = stmt.getGeneratedKeys()) {
        if (!keyRs.next()) {
          throw new IllegalStateException(
              "We are expecting to get the generated keys when inserting a new message");
        }
        int messageId = keyRs.getInt(1);
        for (Actor actor : email.header().actors()) {
          saveActor(con, messageId, actor);
        }
        indexSearchDocument(con, messageId, email);
        return messageId;
      }
    }
  }

  private static void saveActor(Connection con, int messageId, Actor actor) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO actors (message_id, type, name, email_address) VALUES (?, ?, ?, ?)
                """)) {
      stmt.setInt(1, messageId);
      stmt.setInt(2, actor.type().getValue());
      stmt.setString(3, actor.name().orElseGet(() -> null));
      stmt.setString(4, actor.emailAddress());
      stmt.executeUpdate();
    }
  }

  @Override
  public EmailPage getMessages(
      int folderId,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    String orderByString;
    String operatorString;
    if (order == SortOrder.DESCENDING) {
      orderByString = direction == PageDirection.RIGHT ? "DESC" : "ASC";
      operatorString = direction == PageDirection.RIGHT ? "<" : ">";
    } else {
      orderByString = direction == PageDirection.RIGHT ? "ASC" : "DESC";
      operatorString = direction == PageDirection.RIGHT ? ">" : "<";
    }
    // We do offset based paging. In order for this to work our sorting attribute needs to be
    // unique, so we add the message id as a second discriminator
    // if we would not do this, we would skip over all messages that have the same received date
    // which is not unlikely as the precision is only seconds
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT %s
                FROM messages
                WHERE folder_id = ? AND (received_date_epoch_s, id) %s (?, ?) ORDER BY received_date_epoch_s %s, id %s LIMIT ?
                """
            .formatted(MESSAGE_HEADER_COLUMNS, operatorString, orderByString, orderByString),
        stmt -> {
          stmt.setInt(1, folderId);
          stmt.setLong(2, dateTimeOffsetEpochSeconds);
          stmt.setInt(3, offsetMessageId);
          stmt.setInt(4, pageSize + 1);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Email> messages = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  // TODO: profile this and potentially optimise by directly joining? Or by
                  // gathering message ids and getting all actors in batch
                  List<Actor> actors = getActors(messageId);
                  messages.add(convertRowToListEmail(rs, actors));
                }
                boolean hasMoreResults = messages.size() > pageSize;
                if (hasMoreResults) {
                  messages.remove(messages.size() - 1);
                }
                if (direction == PageDirection.LEFT) {
                  Collections.reverse(messages);
                }
                // TODO: we are probably missing the case where we have an offset that is equal to
                // the first el
                boolean hasLeft =
                    switch (direction) {
                      case LEFT -> hasMoreResults;
                      case RIGHT ->
                          !((order == SortOrder.ASCENDING && dateTimeOffsetEpochSeconds == 0)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE));
                    };
                boolean hasRight =
                    switch (direction) {
                      case LEFT ->
                          !((order == SortOrder.ASCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == 0));
                      case RIGHT -> hasMoreResults;
                    };
                return new EmailPage(messages, hasLeft, hasRight, new PageParams(direction, order));
              });
        });
  }

  @Override
  public int getMessageCount(int accountId, int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT COUNT(*) FROM messages WHERE folder_id = ?",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting messages");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public Map<Integer, Integer> countUnreadByFolder(int accountId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT m.folder_id, COUNT(*)
            FROM messages m
            JOIN folders f ON f.id = m.folder_id
            WHERE f.account_id = ? AND m.read = 0
            GROUP BY m.folder_id""",
        stmt -> {
          stmt.setInt(1, accountId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                Map<Integer, Integer> counts = new HashMap<>();
                while (rs.next()) {
                  counts.put(rs.getInt(1), rs.getInt(2));
                }
                return counts;
              });
        });
  }

  @Override
  public int countNewSinceLastView(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT COUNT(*)
            FROM messages m
            JOIN folders f ON f.id = m.folder_id
            WHERE m.folder_id = ?
              AND m.received_date_epoch_s > COALESCE(f.last_viewed_epoch_s, 0)""",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting new messages");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public long maxReceivedEpochSeconds(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT COALESCE(MAX(received_date_epoch_s), 0)
            FROM messages
            WHERE folder_id = ?""",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when reading max received date");
                }
                return rs.getLong(1);
              });
        });
  }

  @Override
  public List<EmailHeader> getMessagesNewerThan(
      int folderId, long afterReceivedEpochSeconds, int afterMessageId, int limit) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT %s
            FROM messages
            WHERE folder_id = ?
              AND (received_date_epoch_s, id) > (?, ?)
            ORDER BY received_date_epoch_s DESC, id DESC
            LIMIT ?"""
            .formatted(MESSAGE_HEADER_COLUMNS),
        stmt -> {
          stmt.setInt(1, folderId);
          stmt.setLong(2, afterReceivedEpochSeconds);
          stmt.setInt(3, afterMessageId);
          stmt.setInt(4, limit);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<EmailHeader> headers = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  headers.add(convertRowToHeader(rs, actors));
                }
                return headers;
              });
        });
  }

  @Override
  public EmailPage searchMessages(
      int accountId,
      String query,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    String orderByString;
    String operatorString;
    if (order == SortOrder.DESCENDING) {
      orderByString = direction == PageDirection.RIGHT ? "DESC" : "ASC";
      operatorString = direction == PageDirection.RIGHT ? "<" : ">";
    } else {
      orderByString = direction == PageDirection.RIGHT ? "ASC" : "DESC";
      operatorString = direction == PageDirection.RIGHT ? ">" : "<";
    }
    String matchQuery = SearchQueryUtils.toFtsMatchQuery(query);
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT %s
                FROM messages m
                JOIN message_search ON message_search.rowid = m.id
                JOIN folders f ON f.id = m.folder_id
                WHERE f.account_id = ?
                  AND message_search MATCH ?
                  AND (m.received_date_epoch_s, m.id) %s (?, ?)
                ORDER BY m.received_date_epoch_s %s, m.id %s
                LIMIT ?
                """
            .formatted(
                QUALIFIED_MESSAGE_HEADER_COLUMNS, operatorString, orderByString, orderByString),
        stmt -> {
          bindSearchParams(
              stmt,
              accountId,
              matchQuery,
              dateTimeOffsetEpochSeconds,
              offsetMessageId,
              pageSize + 1);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Email> messages = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  messages.add(convertRowToListEmail(rs, actors));
                }
                boolean hasMoreResults = messages.size() > pageSize;
                if (hasMoreResults) {
                  messages.removeLast();
                }
                if (direction == PageDirection.LEFT) {
                  Collections.reverse(messages);
                }
                boolean hasLeft =
                    switch (direction) {
                      case LEFT -> hasMoreResults;
                      case RIGHT ->
                          !((order == SortOrder.ASCENDING && dateTimeOffsetEpochSeconds == 0)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE));
                    };
                boolean hasRight =
                    switch (direction) {
                      case LEFT ->
                          !((order == SortOrder.ASCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == 0));
                      case RIGHT -> hasMoreResults;
                    };
                return new EmailPage(messages, hasLeft, hasRight, new PageParams(direction, order));
              });
        });
  }

  @Override
  public int getSearchMessageCount(int accountId, String query) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT COUNT(*)
                FROM messages m
                JOIN message_search ON message_search.rowid = m.id
                JOIN folders f ON f.id = m.folder_id
                WHERE f.account_id = ?
                  AND message_search MATCH ?
                """,
        stmt -> {
          bindSearchFilterParams(stmt, accountId, SearchQueryUtils.toFtsMatchQuery(query));
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting search results");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public void removeBatchByUid(List<Long> batch) {
    if (batch.size() > DbEmailRepository.IN_BATCH_SIZE) {
      throw new IllegalStateException(
          "Only allowed to delete batches of maximally %s messages"
              .formatted(DbEmailRepository.IN_BATCH_SIZE));
    }
    String inString = batch.stream().map(msgId -> "?").collect(Collectors.joining(", "));
    DbUtil.withConConsumer(
        ds,
        con -> {
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM message_search WHERE rowid IN (SELECT id FROM messages WHERE imap_uid IN (%s))"
                  .formatted(inString),
              stmt -> {
                for (int i = 0; i < batch.size(); i++) {
                  stmt.setLong(i + 1, batch.get(i));
                }
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM messages WHERE imap_uid IN (%s)".formatted(inString),
              stmt -> {
                for (int i = 0; i < batch.size(); i++) {
                  stmt.setLong(i + 1, batch.get(i));
                }
                stmt.executeUpdate();
              });
        });
  }

  @Override
  public void deleteById(int id) {
    moveToMappedSpecialFolderById(id, MailboxActionType.DELETE, FolderSpecialUse.TRASH);
  }

  @Override
  public void archiveById(int id) {
    moveToMappedSpecialFolderById(id, MailboxActionType.ARCHIVE, FolderSpecialUse.ARCHIVE);
  }

  @Override
  public void markSpamById(int id) {
    moveToMappedSpecialFolderById(id, MailboxActionType.MARK_SPAM, FolderSpecialUse.JUNK);
  }

  @Override
  public void moveToFolderById(int id, int targetFolderId) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<Integer> messageAccountId = findMessageAccountId(con, id);
            Optional<Integer> targetAccountId = findFolderAccountId(con, targetFolderId);
            if (messageAccountId.isEmpty()
                || targetAccountId.isEmpty()
                || !messageAccountId.get().equals(targetAccountId.get())) {
              con.rollback();
              return;
            }

            Optional<MessageActionContext> context = findMessageActionContext(con, id);
            Optional<TargetFolderContext> target = findTargetFolderContext(con, targetFolderId);
            if (context.isEmpty() || target.isEmpty()) {
              con.rollback();
              return;
            }

            moveMessageToFolder(con, id, targetFolderId);
            enqueueAction(con, context.get(), MailboxActionType.MOVE, target.get(), null, null);
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private void moveToMappedSpecialFolderById(
      int id, MailboxActionType actionType, FolderSpecialUse specialUse) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<MessageActionContext> context = findMessageActionContext(con, id);
            if (context.isEmpty()) {
              con.rollback();
              return;
            }
            Optional<TargetFolderContext> target =
                findMappedTargetFolderContext(con, context.get().accountId(), specialUse);
            if (target.isEmpty()) {
              con.rollback();
              return;
            }
            moveMessageToFolder(con, id, target.get().folderId());
            enqueueAction(con, context.get(), actionType, target.get(), specialUse, null);
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private static Optional<Integer> findMessageAccountId(Connection con, int messageId)
      throws SQLException {
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

  private static Optional<Integer> findFolderAccountId(Connection con, int folderId)
      throws SQLException {
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

  private static Optional<MessageActionContext> findMessageActionContext(
      Connection con, int messageId) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                SELECT f.account_id, m.folder_id, f.remote_name, f.uidvalidity, m.imap_uid
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
                rs.getLong(5)));
      }
    }
  }

  private static Optional<TargetFolderContext> findMappedTargetFolderContext(
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

  private static Optional<TargetFolderContext> findTargetFolderContext(Connection con, int folderId)
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

  private static boolean coalesceDraftUpsert(
      Connection con, int draftId, ZonedDateTime nextAttemptAt) throws SQLException {
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
      stmt.setString(2, toISOString(nextAttemptAt));
      stmt.setLong(3, nextAttemptAt.toEpochSecond());
      stmt.setString(4, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(5, Integer.toString(draftId));
      return stmt.executeUpdate() > 0;
    }
  }

  private static void cancelPendingDraftUpserts(Connection con, int draftId) throws SQLException {
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

  private static void enqueueDraftUpsertAction(
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
      stmt.setString(7, toISOString(nextAttemptAt));
      stmt.setLong(8, nextAttemptAt.toEpochSecond());
      stmt.executeUpdate();
    }
  }

  private static void enqueueDraftDeleteAction(
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

  private static boolean hasUnresolvedDraftDelete(Connection con, int draftId) throws SQLException {
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

  private static Optional<DraftEnqueueContext> findDraftEnqueueContext(Connection con, int draftId)
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

  private static void enqueueAppendSentAction(
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

  private static boolean hasUnresolvedAppendSent(Connection con, int outboundMessageId)
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

  private static Optional<Integer> findOutboundMessageAccountId(
      Connection con, int outboundMessageId) throws SQLException {
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

  private static void enqueueAction(
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

  private static void moveMessageToFolder(Connection con, int messageId, int targetFolderId)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement("UPDATE messages SET folder_id = ? WHERE id = ?")) {
      stmt.setInt(1, targetFolderId);
      stmt.setInt(2, messageId);
      stmt.executeUpdate();
    }
  }

  private static Long getNullableLong(ResultSet rs, int columnIndex) throws SQLException {
    long value = rs.getLong(columnIndex);
    return rs.wasNull() ? null : value;
  }

  private static Integer getNullableInt(ResultSet rs, int columnIndex) throws SQLException {
    int value = rs.getInt(columnIndex);
    return rs.wasNull() ? null : value;
  }

  private static MailboxSyncStatusCounts getMailboxSyncStatusCounts(Connection con, int accountId)
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

  private static List<MailboxActionQueueRow> getMailboxSyncStatusRows(Connection con, int accountId)
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

  private static List<MailboxActionQueueRow> findDueMailboxActions(
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
                    EnumSql.inClause(MailboxActionType.BACKGROUND_SYNCABLE)))) {
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

  private static Optional<MailboxActionQueueRow> findMailboxAction(
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

  private static MailboxActionQueueRow toMailboxActionQueueRow(ResultSet rs) throws SQLException {
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

  private record MessageActionContext(
      int messageId,
      int accountId,
      int sourceFolderId,
      String sourceRemoteName,
      Long sourceUidValidity,
      long sourceUid) {}

  private record TargetFolderContext(
      int folderId, String remoteName, FolderSpecialUse specialUse) {}

  private record DraftEnqueueContext(
      int accountId, Optional<Long> remoteImapUid, Optional<Long> remoteUidValidity) {}

  private record MailboxSyncStatusCounts(
      int pendingCount,
      int retryingCount,
      int failedCount,
      int conflictCount,
      boolean needsAttention) {}

  private static String toISOString(ZonedDateTime dateTime) {
    return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static ZonedDateTime fromISOString(String isoDate) {
    return ZonedDateTime.parse(isoDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static void bindSearchFilterParams(
      java.sql.PreparedStatement stmt, int accountId, String matchQuery) throws SQLException {
    stmt.setInt(1, accountId);
    stmt.setString(2, matchQuery);
  }

  private static void bindSearchParams(
      java.sql.PreparedStatement stmt,
      int accountId,
      String matchQuery,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      int limit)
      throws SQLException {
    bindSearchFilterParams(stmt, accountId, matchQuery);
    stmt.setLong(3, dateTimeOffsetEpochSeconds);
    stmt.setInt(4, offsetMessageId);
    stmt.setInt(5, limit);
  }

  private static void indexSearchDocument(Connection con, int messageId, Email email)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO message_search(rowid, subject, body_excerpt, body, actors)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      stmt.setInt(1, messageId);
      stmt.setString(2, Optional.ofNullable(email.header().subject()).orElse(""));
      stmt.setString(3, Optional.ofNullable(email.header().bodyExcerpt()).orElse(""));
      stmt.setString(4, Optional.ofNullable(email.body()).orElse(""));
      stmt.setString(5, formatSearchActors(email.header().actors()));
      stmt.executeUpdate();
    }
  }

  private static String formatSearchActors(List<Actor> actors) {
    return actors.stream()
        .map(actor -> (actor.name().orElse("") + " " + actor.emailAddress()).trim())
        .filter(actor -> !actor.isBlank())
        .collect(Collectors.joining(" "));
  }
}
