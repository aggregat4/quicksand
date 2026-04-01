package net.aggregat4.quicksand.jobs;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.StoredAttachment;
import net.aggregat4.quicksand.repository.AttachmentRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.OutboundMessageRepository;

public class MailSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailSender.class);

    private static final long INITIAL_DELAY_SECONDS = 0;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final DbAccountRepository accountRepository;
    private final OutboundMessageRepository outboundMessageRepository;
    private final AttachmentRepository attachmentRepository;
    private final Clock clock;
    private final long sendPeriodInSeconds;
    private final int maxAttempts;
    private final long retryDelaySeconds;

    public MailSender(
            DbAccountRepository accountRepository,
            OutboundMessageRepository outboundMessageRepository,
            AttachmentRepository attachmentRepository,
            Clock clock,
            long sendPeriodInSeconds,
            int maxAttempts,
            long retryDelaySeconds) {
        this.accountRepository = accountRepository;
        this.outboundMessageRepository = outboundMessageRepository;
        this.attachmentRepository = attachmentRepository;
        this.clock = clock;
        this.sendPeriodInSeconds = sendPeriodInSeconds;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::sendQueuedMessages, INITIAL_DELAY_SECONDS, sendPeriodInSeconds, TimeUnit.SECONDS);
    }

    public void sendNow() {
        sendQueuedMessages();
    }

    public void stop() {
        scheduler.shutdown();
    }

    void sendQueuedMessages() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (OutboundMessage message : outboundMessageRepository.findDeliverable(now)) {
            Account account = accountRepository.getAccount(message.accountId());
            try {
                deliver(account, message, attachmentRepository.findStoredByOutboundMessageId(message.id()));
                outboundMessageRepository.markSent(message.id(), now);
            } catch (MessagingException | UnsupportedEncodingException e) {
                LOGGER.warn("Failed to send outbound message {}", message.id(), e);
                String abbreviatedError = abbreviateError(e);
                if (message.attemptCount() + 1 >= maxAttempts) {
                    outboundMessageRepository.markFailed(message.id(), abbreviatedError);
                } else {
                    outboundMessageRepository.scheduleRetry(message.id(), abbreviatedError, now.plusSeconds(backoffSeconds(message.attemptCount())));
                }
            }
        }
    }

    private void deliver(Account account, OutboundMessage message, List<StoredAttachment> attachments)
            throws MessagingException, UnsupportedEncodingException {
        Session session = Session.getInstance(smtpProperties(account), null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress(message.fromAddress(), blankToNull(message.fromName())));
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
                attachmentPart.setDataHandler(new DataHandler(
                        new ByteArrayDataSource(attachment.content(), attachment.mediaType().text())));
                multipart.addBodyPart(attachmentPart);
            }
            mimeMessage.setContent(multipart);
        }
        mimeMessage.saveChanges();

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(account.smtpHost(), account.smtpPort(), account.smtpUsername(), account.smtpPassword());
            transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients());
        }
    }

    private static void setRecipients(MimeMessage mimeMessage, Message.RecipientType recipientType, String rawRecipients)
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

    private static String abbreviateError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String collapsed = message.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 237) + "...";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long backoffSeconds(int attemptCount) {
        return retryDelaySeconds * (1L << Math.max(0, attemptCount));
    }
}
