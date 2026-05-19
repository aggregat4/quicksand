package net.aggregat4.quicksand.domain;

public enum MailboxActionResolutionType {
  ROLLED_BACK,
  ABANDONED,
  ABANDONED_BY_RESET,
  DISMISSED,
  RESOLVED_REMOTE_MATCHED
}
