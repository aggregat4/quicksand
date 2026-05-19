package net.aggregat4.quicksand.jobs;

import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;

final class DraftMimeMessageBuilder {

  private DraftMimeMessageBuilder() {}

  static MimeMessage build(Account account, Draft draft)
      throws MessagingException, UnsupportedEncodingException {
    Session session = Session.getInstance(smtpProperties(account), null);
    MimeMessage mimeMessage = new MimeMessage(session);
    String fromAddress = primaryAccountAddress(account);
    mimeMessage.setFrom(new InternetAddress(fromAddress, account.name()));
    setRecipients(mimeMessage, Message.RecipientType.TO, draft.to());
    setRecipients(mimeMessage, Message.RecipientType.CC, draft.cc());
    setRecipients(mimeMessage, Message.RecipientType.BCC, draft.bcc());
    mimeMessage.setSubject(
        draft.subject() == null || draft.subject().isBlank() ? "(no subject)" : draft.subject(),
        StandardCharsets.UTF_8.name());
    mimeMessage.setText(draft.body() == null ? "" : draft.body(), StandardCharsets.UTF_8.name());
    mimeMessage.setFlag(Flags.Flag.DRAFT, true);
    mimeMessage.saveChanges();
    return mimeMessage;
  }

  private static void setRecipients(
      MimeMessage mimeMessage, Message.RecipientType recipientType, String rawRecipients)
      throws MessagingException {
    if (rawRecipients == null || rawRecipients.isBlank()) {
      return;
    }
    mimeMessage.setRecipients(recipientType, InternetAddress.parse(rawRecipients, false));
  }

  private static String primaryAccountAddress(Account account) {
    if (account.smtpUsername() != null && account.smtpUsername().contains("@")) {
      return account.smtpUsername();
    }
    if (account.imapUsername() != null && account.imapUsername().contains("@")) {
      return account.imapUsername();
    }
    return "draft@local.invalid";
  }

  private static Properties smtpProperties(Account account) {
    Properties properties = new Properties();
    properties.put("mail.smtp.host", account.smtpHost());
    properties.put("mail.smtp.port", Integer.toString(account.smtpPort()));
    properties.put("mail.smtp.auth", "true");
    return properties;
  }
}
