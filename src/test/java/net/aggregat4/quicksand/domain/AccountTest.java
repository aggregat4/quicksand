package net.aggregat4.quicksand.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccountTest {

  @Test
  void toStringRedactsPasswords() {
    Account account =
        new Account(
            1,
            "Example",
            "imap.example.com",
            993,
            "imap-user",
            "imap-secret",
            "smtp.example.com",
            587,
            "smtp-user",
            "smtp-secret");

    String rendered = account.toString();

    assertTrue(rendered.contains("imap-user"));
    assertTrue(rendered.contains("smtp-user"));
    assertFalse(rendered.contains("imap-secret"));
    assertFalse(rendered.contains("smtp-secret"));
    assertTrue(rendered.contains("[REDACTED]"));
  }
}
