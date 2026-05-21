package net.aggregat4.quicksand.jobs;

import java.util.Properties;
import net.aggregat4.quicksand.domain.Account;

/** Jakarta Mail session properties inferred from standard IMAP/SMTP port conventions. */
public final class JakartaMailSessionProperties {

  private static final String CONNECTION_TIMEOUT = "15000";
  private static final String IO_TIMEOUT = "15000";

  private JakartaMailSessionProperties() {}

  public static Properties imap(Account account) {
    Properties properties = new Properties();
    properties.put("mail.imap.connectiontimeout", CONNECTION_TIMEOUT);
    properties.put("mail.imap.timeout", IO_TIMEOUT);
    applyImapTransportSecurity(properties, account.imapPort());
    return properties;
  }

  public static Properties smtp(Account account) {
    Properties properties = new Properties();
    properties.put("mail.smtp.host", account.smtpHost());
    properties.put("mail.smtp.port", Integer.toString(account.smtpPort()));
    properties.put("mail.smtp.connectiontimeout", CONNECTION_TIMEOUT);
    properties.put("mail.smtp.timeout", IO_TIMEOUT);
    properties.put("mail.smtp.auth", "true");
    applySmtpTransportSecurity(properties, account.smtpPort());
    return properties;
  }

  static void applyImapTransportSecurity(Properties properties, int imapPort) {
    if (imapPort == 993) {
      properties.put("mail.imap.ssl.enable", "true");
      return;
    }
    if (imapPort == 143) {
      properties.put("mail.imap.starttls.enable", "true");
      properties.put("mail.imap.starttls.required", "true");
    }
  }

  static void applySmtpTransportSecurity(Properties properties, int smtpPort) {
    if (smtpPort == 465) {
      properties.put("mail.smtp.ssl.enable", "true");
      return;
    }
    if (smtpPort == 25 || smtpPort == 587) {
      properties.put("mail.smtp.starttls.enable", "true");
      properties.put("mail.smtp.starttls.required", "true");
    }
  }
}
