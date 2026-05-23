package net.aggregat4.quicksand.domain;

public record Account(
    int id,
    String name,
    String imapHost,
    int imapPort,
    String imapUsername,
    String imapPassword,
    String smtpHost,
    int smtpPort,
    String smtpUsername,
    String smtpPassword) {
  private static final String REDACTED = "[REDACTED]";

  @Override
  public String toString() {
    return "Account[id="
        + id
        + ", name="
        + name
        + ", imapHost="
        + imapHost
        + ", imapPort="
        + imapPort
        + ", imapUsername="
        + imapUsername
        + ", imapPassword="
        + REDACTED
        + ", smtpHost="
        + smtpHost
        + ", smtpPort="
        + smtpPort
        + ", smtpUsername="
        + smtpUsername
        + ", smtpPassword="
        + REDACTED
        + "]";
  }
}
