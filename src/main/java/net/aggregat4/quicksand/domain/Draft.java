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
    long updatedAtEpochSeconds) {

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
        updatedAt.toEpochSecond());
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
        updatedAt.toEpochSecond());
  }
}
