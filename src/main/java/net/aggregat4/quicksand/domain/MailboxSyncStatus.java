package net.aggregat4.quicksand.domain;

import java.util.List;

public record MailboxSyncStatus(
    int pendingCount,
    int retryingCount,
    int failedCount,
    int conflictCount,
    boolean needsAttention,
    List<MailboxActionQueueRow> actions) {

  public int unresolvedCount() {
    return pendingCount + retryingCount + failedCount + conflictCount;
  }
}
