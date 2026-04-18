package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Objects;

public record EmailHeader(
    int id,
    long imapUid,
    List<Actor> actors,
    String subject,
    ZonedDateTime sentDateTime,
    long sentDateTimeEpochSeconds,
    ZonedDateTime receivedDateTime,
    long receivedDateTimeEpochSeconds,
    String bodyExcerpt,
    boolean starred,
    boolean attachment,
    boolean read) {
  private static final DateTimeFormatter CURRENT_YEAR_FORMATTER =
      DateTimeFormatter.ofPattern("dd LLL");
  private static final DateTimeFormatter LONG_DATE_FORMATTER =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

  public String shortFormattedReceivedDate() {
    return CURRENT_YEAR_FORMATTER.format(receivedDateTime.toLocalDate());
  }

  public String longFormattedReceivedDate() {
    return LONG_DATE_FORMATTER.format(receivedDateTime.toLocalDateTime());
  }

  public String shortFormattedSentDate() {
    return CURRENT_YEAR_FORMATTER.format(sentDateTime.toLocalDate());
  }

  public String longFormattedSentDate() {
    return LONG_DATE_FORMATTER.format(sentDateTime.toLocalDateTime());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof EmailHeader that)) return false;
    return id == that.id;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  private List<Actor> getActors(ActorType type) {
    return actors.stream().filter(a -> a.type() == type).toList();
  }

  public Actor getSender() {
    List<Actor> senders = getActors(ActorType.SENDER);
    if (!senders.isEmpty()) {
      return senders.getFirst();
    }
    if (!actors.isEmpty()) {
      return actors.getFirst();
    }
    return new Actor(ActorType.SENDER, "unknown@invalid", java.util.Optional.of("Unknown sender"));
  }

  public List<Actor> getRecipients() {
    return getActors(ActorType.TO);
  }

  public List<Actor> getCCRecipients() {
    return getActors(ActorType.CC);
  }

  public List<Actor> getBCCRecipients() {
    return getActors(ActorType.BCC);
  }
}
