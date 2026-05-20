package net.aggregat4.quicksand.domain;

public record MailboxActionQueueRow(
    int id,
    int accountId,
    int messageId,
    String messageSubject,
    MailboxActionType actionType,
    Integer sourceFolderId,
    String sourceRemoteName,
    Long sourceUidValidity,
    Long sourceUid,
    Integer targetFolderId,
    String targetRemoteName,
    FolderSpecialUse targetSpecialUse,
    MailboxActionStatus status,
    MailboxActionExecutionState executionState,
    MailboxActionResolutionType resolutionType,
    int attemptCount,
    String nextAttemptAt,
    String lastError,
    String createdAt,
    String updatedAt,
    String payloadJson) {

  public boolean canRetryNow() {
    return resolutionType == null
        && (status == MailboxActionStatus.FAILED_RETRYABLE
            || status == MailboxActionStatus.CONFLICT
            || status == MailboxActionStatus.APPLYING);
  }

  public boolean canDismiss() {
    return resolutionType == null
        && (status == MailboxActionStatus.FAILED_PERMANENT
            || status == MailboxActionStatus.CONFLICT);
  }

  public boolean canAbandon() {
    return resolutionType == null && MailboxActionStatus.SYNC_STATUS_VISIBLE.contains(status);
  }

  public boolean canRollbackLocalMove() {
    if (resolutionType != null || !MailboxActionType.MOVE_LIKE.contains(actionType)) {
      return false;
    }
    if (executionState != MailboxActionExecutionState.NOT_ATTEMPTED
        || sourceFolderId == null
        || messageId <= 0) {
      return false;
    }
    return status == MailboxActionStatus.FAILED_PERMANENT
        || status == MailboxActionStatus.CONFLICT
        || status == MailboxActionStatus.FAILED_RETRYABLE;
  }
}
