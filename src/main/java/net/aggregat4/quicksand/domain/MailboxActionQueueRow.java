package net.aggregat4.quicksand.domain;

public record MailboxActionQueueRow(
    int id,
    int accountId,
    String messageSubject,
    MailboxActionType actionType,
    String sourceRemoteName,
    Long sourceUidValidity,
    Long sourceUid,
    String targetRemoteName,
    FolderSpecialUse targetSpecialUse,
    MailboxActionStatus status,
    MailboxActionExecutionState executionState,
    MailboxActionResolutionType resolutionType,
    int attemptCount,
    String nextAttemptAt,
    String lastError,
    String createdAt,
    String updatedAt) {}
