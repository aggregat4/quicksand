package net.aggregat4.quicksand.domain;

import java.util.List;

public enum MailboxActionStatus {
  PENDING,
  APPLYING,
  SUCCEEDED,
  FAILED_RETRYABLE,
  FAILED_PERMANENT,
  CONFLICT;

  public static final List<MailboxActionStatus> CLAIMABLE = List.of(PENDING, FAILED_RETRYABLE);

  public static final List<MailboxActionStatus> PENDING_OR_APPLYING = List.of(PENDING, APPLYING);

  public static final List<MailboxActionStatus> SYNC_STATUS_VISIBLE =
      List.of(PENDING, APPLYING, FAILED_RETRYABLE, FAILED_PERMANENT, CONFLICT);

  public static final List<MailboxActionStatus> UNRESOLVED =
      List.of(PENDING, APPLYING, FAILED_RETRYABLE, FAILED_PERMANENT, CONFLICT);
}
