package net.aggregat4.quicksand.jobs;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.StoredAttachment;
import net.aggregat4.quicksand.repository.AttachmentRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(MailSender.class);

  private static final long INITIAL_DELAY_SECONDS = 0;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final DbAccountRepository accountRepository;
  private final OutboundMessageRepository outboundMessageRepository;
  private final AttachmentRepository attachmentRepository;
  private final EmailRepository emailRepository;
  private final Clock clock;
  private final long sendPeriodInSeconds;
  private final int maxAttempts;
  private final long retryDelaySeconds;

  public MailSender(
      DbAccountRepository accountRepository,
      OutboundMessageRepository outboundMessageRepository,
      AttachmentRepository attachmentRepository,
      EmailRepository emailRepository,
      Clock clock,
      long sendPeriodInSeconds,
      int maxAttempts,
      long retryDelaySeconds) {
    this.accountRepository = accountRepository;
    this.outboundMessageRepository = outboundMessageRepository;
    this.attachmentRepository = attachmentRepository;
    this.emailRepository = emailRepository;
    this.clock = clock;
    this.sendPeriodInSeconds = sendPeriodInSeconds;
    this.maxAttempts = maxAttempts;
    this.retryDelaySeconds = retryDelaySeconds;
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(
        this::sendQueuedMessages, INITIAL_DELAY_SECONDS, sendPeriodInSeconds, TimeUnit.SECONDS);
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
        List<StoredAttachment> attachments =
            attachmentRepository.findStoredByOutboundMessageId(message.id());
        deliver(account, message, attachments);
        outboundMessageRepository.markSent(message.id(), now);
        emailRepository.enqueueAppendSent(message.id());
      } catch (MessagingException | UnsupportedEncodingException e) {
        LOGGER.warn("Failed to send outbound message {}", message.id(), e);
        String abbreviatedError = abbreviateError(e);
        if (message.attemptCount() + 1 >= maxAttempts) {
          outboundMessageRepository.markFailed(message.id(), abbreviatedError);
        } else {
          outboundMessageRepository.scheduleRetry(
              message.id(),
              abbreviatedError,
              now.plusSeconds(backoffSeconds(message.attemptCount())));
        }
      }
    }
  }

  private void deliver(Account account, OutboundMessage message, List<StoredAttachment> attachments)
      throws MessagingException, UnsupportedEncodingException {
    MimeMessage mimeMessage = OutboundMimeMessageBuilder.build(account, message, attachments);
    Session session = mimeMessage.getSession();

    try (Transport transport = session.getTransport("smtp")) {
      transport.connect(
          account.smtpHost(), account.smtpPort(), account.smtpUsername(), account.smtpPassword());
      transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients());
    }
  }

  private static String abbreviateError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    String collapsed = message.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 237) + "...";
  }

  private long backoffSeconds(int attemptCount) {
    return retryDelaySeconds * (1L << Math.max(0, attemptCount));
  }
}
