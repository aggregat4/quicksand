package net.aggregat4.quicksand.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Properties;
import net.aggregat4.quicksand.domain.Account;
import org.junit.jupiter.api.Test;

class JakartaMailSessionPropertiesTest {

  private static final Account ACCOUNT =
      new Account(
          1,
          "Test",
          "imap.example.com",
          993,
          "user@example.com",
          "secret",
          "smtp.example.com",
          587,
          "user@example.com",
          "secret");

  @Test
  void imapPort993EnablesSsl() {
    Properties properties = JakartaMailSessionProperties.imap(accountWithImapPort(993));

    assertEquals("true", properties.get("mail.imap.ssl.enable"));
    assertNull(properties.get("mail.imap.starttls.enable"));
  }

  @Test
  void imapPort143EnablesStartTls() {
    Properties properties = JakartaMailSessionProperties.imap(accountWithImapPort(143));

    assertEquals("true", properties.get("mail.imap.starttls.enable"));
    assertEquals("true", properties.get("mail.imap.starttls.required"));
    assertNull(properties.get("mail.imap.ssl.enable"));
  }

  @Test
  void smtpPort465EnablesSsl() {
    Properties properties = JakartaMailSessionProperties.smtp(accountWithSmtpPort(465));

    assertEquals("true", properties.get("mail.smtp.ssl.enable"));
    assertNull(properties.get("mail.smtp.starttls.enable"));
  }

  @Test
  void smtpPort587EnablesStartTls() {
    Properties properties = JakartaMailSessionProperties.smtp(accountWithSmtpPort(587));

    assertEquals("true", properties.get("mail.smtp.starttls.enable"));
    assertEquals("true", properties.get("mail.smtp.starttls.required"));
    assertNull(properties.get("mail.smtp.ssl.enable"));
  }

  @Test
  void smtpPort25EnablesStartTls() {
    Properties properties = JakartaMailSessionProperties.smtp(accountWithSmtpPort(25));

    assertEquals("true", properties.get("mail.smtp.starttls.enable"));
    assertEquals("true", properties.get("mail.smtp.starttls.required"));
    assertNull(properties.get("mail.smtp.ssl.enable"));
  }

  @Test
  void cleartextPortsLeaveTransportSecurityUnset() {
    Properties imap = JakartaMailSessionProperties.imap(accountWithImapPort(4143));
    Properties smtp = JakartaMailSessionProperties.smtp(accountWithSmtpPort(4025));

    assertNull(imap.get("mail.imap.ssl.enable"));
    assertNull(imap.get("mail.imap.starttls.enable"));
    assertNull(smtp.get("mail.smtp.ssl.enable"));
    assertNull(smtp.get("mail.smtp.starttls.enable"));
  }

  private static Account accountWithImapPort(int imapPort) {
    return new Account(
        ACCOUNT.id(),
        ACCOUNT.name(),
        ACCOUNT.imapHost(),
        imapPort,
        ACCOUNT.imapUsername(),
        ACCOUNT.imapPassword(),
        ACCOUNT.smtpHost(),
        ACCOUNT.smtpPort(),
        ACCOUNT.smtpUsername(),
        ACCOUNT.smtpPassword());
  }

  private static Account accountWithSmtpPort(int smtpPort) {
    return new Account(
        ACCOUNT.id(),
        ACCOUNT.name(),
        ACCOUNT.imapHost(),
        ACCOUNT.imapPort(),
        ACCOUNT.imapUsername(),
        ACCOUNT.imapPassword(),
        ACCOUNT.smtpHost(),
        smtpPort,
        ACCOUNT.smtpUsername(),
        ACCOUNT.smtpPassword());
  }
}
