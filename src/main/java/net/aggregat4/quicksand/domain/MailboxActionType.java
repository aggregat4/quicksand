package net.aggregat4.quicksand.domain;

import java.util.List;

public enum MailboxActionType {
  MARK_READ,
  MARK_UNREAD,
  MOVE,
  DELETE,
  ARCHIVE,
  MARK_SPAM,
  APPEND_SENT,
  UPSERT_DRAFT,
  DELETE_DRAFT;

  public static final List<MailboxActionType> MOVE_LIKE = List.of(MOVE, DELETE, ARCHIVE, MARK_SPAM);

  public static final List<MailboxActionType> READ_STATE_SYNCABLE = List.of(MARK_READ, MARK_UNREAD);

  public static final List<MailboxActionType> BACKGROUND_SYNCABLE =
      List.of(
          MARK_READ,
          MARK_UNREAD,
          MOVE,
          DELETE,
          ARCHIVE,
          MARK_SPAM,
          APPEND_SENT,
          UPSERT_DRAFT,
          DELETE_DRAFT);
}
