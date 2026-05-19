package net.aggregat4.quicksand.jobs;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.StoredAttachment;

final class OutboundMimeMessageBuilder {

  private OutboundMimeMessageBuilder() {}

  static MimeMessage build(
      Account account, OutboundMessage message, List<StoredAttachment> attachments)
      throws MessagingException, UnsupportedEncodingException {
    Session session = Session.getInstance(smtpProperties(account), null);
    MimeMessage mimeMessage = new MimeMessage(session);
    mimeMessage.setFrom(
        new InternetAddress(message.fromAddress(), blankToNull(message.fromName())));
    setRecipients(mimeMessage, Message.RecipientType.TO, message.to());
    setRecipients(mimeMessage, Message.RecipientType.CC, message.cc());
    setRecipients(mimeMessage, Message.RecipientType.BCC, message.bcc());
    mimeMessage.setSubject(message.subject(), StandardCharsets.UTF_8.name());
    if (attachments.isEmpty()) {
      mimeMessage.setText(message.body(), StandardCharsets.UTF_8.name());
    } else {
      MimeMultipart multipart = new MimeMultipart();

      MimeBodyPart textPart = new MimeBodyPart();
      textPart.setText(message.body(), StandardCharsets.UTF_8.name());
      multipart.addBodyPart(textPart);

      for (StoredAttachment attachment : attachments) {
        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setFileName(attachment.name());
        attachmentPart.setDataHandler(
            new DataHandler(
                new ByteArrayDataSource(attachment.content(), attachment.mediaType().text())));
        multipart.addBodyPart(attachmentPart);
      }
      mimeMessage.setContent(multipart);
    }
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

  private static Properties smtpProperties(Account account) {
    Properties properties = new Properties();
    properties.put("mail.smtp.host", account.smtpHost());
    properties.put("mail.smtp.port", Integer.toString(account.smtpPort()));
    properties.put("mail.smtp.auth", "true");
    return properties;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
