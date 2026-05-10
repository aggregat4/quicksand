package net.aggregat4.quicksand.domain;

public record MailboxActionQueueRow(
    int id,
    int accountId,
    String messageSubject,
    String actionType,
    String sourceRemoteName,
    Long sourceUidValidity,
    Long sourceUid,
    String targetRemoteName,
    String targetSpecialUse,
    String status,
    String executionState,
    String resolutionType,
    int attemptCount,
    String nextAttemptAt,
    String lastError,
    String createdAt,
    String updatedAt) {}
