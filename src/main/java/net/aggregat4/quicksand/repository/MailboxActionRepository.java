package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;

public interface MailboxActionRepository {
  Set<Long> getPendingMoveLikeActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity);

  void resolveMoveLikeSourceUidsAbsentFromRemote(
      int accountId, String sourceRemoteName, Long sourceUidValidity, Set<Long> remoteUidsPresent);

  void resolveMoveLikeSourceUidsVanished(
      int accountId, String sourceRemoteName, Long sourceUidValidity, Set<Long> vanishedUids);

  void markMoveLikeActionsConflictForUidValidityChange(
      int accountId, int folderId, long newUidValidity, ZonedDateTime now);

  Set<Long> getPendingReadStateActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity);

  MailboxSyncStatus getMailboxSyncStatus(int accountId);

  boolean needsMailboxSyncAttention(int accountId);

  List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit);

  List<MailboxActionQueueRow> claimDueMailboxActions(int accountId, ZonedDateTime now, int limit);

  List<MailboxActionQueueRow> claimDueReadStateActions(ZonedDateTime now, int limit);

  List<MailboxActionQueueRow> claimDueReadStateActions(int accountId, ZonedDateTime now, int limit);

  void markMailboxActionSucceeded(int id, ZonedDateTime now);

  void markMailboxActionAttemptedUnknown(int id, ZonedDateTime now);

  void confirmMailboxMoveApplied(
      int actionId,
      int messageId,
      Integer targetFolderId,
      long targetUidValidity,
      long targetUid,
      ZonedDateTime now);

  void markMailboxActionRetry(int id, String error, ZonedDateTime nextAttempt, ZonedDateTime now);

  void markMailboxActionConflict(int id, String error, ZonedDateTime now);

  void markMailboxActionPermanentFailure(int id, String error, ZonedDateTime now);

  Optional<MailboxActionQueueRow> findMailboxAction(int actionId, int accountId);

  boolean requestMailboxActionRetry(int actionId, int accountId, ZonedDateTime now);

  boolean dismissMailboxAction(int actionId, int accountId, ZonedDateTime now);

  boolean abandonMailboxAction(int actionId, int accountId, ZonedDateTime now);

  boolean rollbackMailboxAction(int actionId, int accountId, ZonedDateTime now);

  void resolveUnresolvedMailboxActions(
      int accountId, MailboxActionResolutionType resolutionType, ZonedDateTime now);

  void clearMirroredMailboxState(int accountId);

  int purgeStaleMailboxActionRows(ZonedDateTime now);

  void enqueueAppendSent(int outboundMessageId);

  void scheduleDraftUpsert(int draftId, ZonedDateTime nextAttemptAt);

  void enqueueDraftDelete(int draftId);

  /**
   * Uses the caller's connection; must not open a nested connection while a transaction is active.
   */
  void enqueueDraftDelete(Connection con, int draftId);
}
