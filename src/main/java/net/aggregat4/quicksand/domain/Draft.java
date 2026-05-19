package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.util.Optional;

public record Draft(
    int id,
    int accountId,
    DraftType type,
    Optional<Integer> sourceMessageId,
    String to,
    String cc,
    String bcc,
    String subject,
    String body,
    boolean queued,
    ZonedDateTime updatedAt,
    long updatedAtEpochSeconds,
    Optional<Long> remoteImapUid,
    Optional<Long> remoteUidValidity) {

  public Draft(
      int id,
      int accountId,
      DraftType type,
      Optional<Integer> sourceMessageId,
      String to,
      String cc,
      String bcc,
      String subject,
      String body,
      boolean queued,
      ZonedDateTime updatedAt,
      long updatedAtEpochSeconds) {
    this(
        id,
        accountId,
        type,
        sourceMessageId,
        to,
        cc,
        bcc,
        subject,
        body,
        queued,
        updatedAt,
        updatedAtEpochSeconds,
        Optional.empty(),
        Optional.empty());
  }

  public Draft withContent(
      String to, String cc, String bcc, String subject, String body, ZonedDateTime updatedAt) {
    return new Draft(
        id,
        accountId,
        type,
        sourceMessageId,
        to,
        cc,
        bcc,
        subject,
        body,
        queued,
        updatedAt,
        updatedAt.toEpochSecond(),
        remoteImapUid,
        remoteUidValidity);
  }

  public Draft markQueued(ZonedDateTime updatedAt) {
    return new Draft(
        id,
        accountId,
        type,
        sourceMessageId,
        to,
        cc,
        bcc,
        subject,
        body,
        true,
        updatedAt,
        updatedAt.toEpochSecond(),
        remoteImapUid,
        remoteUidValidity);
  }

  public Draft withRemoteIdentity(long remoteImapUid, long remoteUidValidity) {
    return new Draft(
        id,
        accountId,
        type,
        sourceMessageId,
        to,
        cc,
        bcc,
        subject,
        body,
        queued,
        updatedAt,
        updatedAtEpochSeconds,
        Optional.of(remoteImapUid),
        Optional.of(remoteUidValidity));
  }
}
