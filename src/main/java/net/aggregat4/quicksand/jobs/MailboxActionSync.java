package net.aggregat4.quicksand.jobs;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailboxActionSync {
  private static final Logger LOGGER = LoggerFactory.getLogger(MailboxActionSync.class);
  private static final long INITIAL_DELAY_SECONDS = 0;
  private static final int BATCH_SIZE = 50;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final DbAccountRepository accountRepository;
  private final EmailRepository emailRepository;
  private final Clock clock;
  private final long syncPeriodInSeconds;
  private final long retryDelaySeconds;

  public MailboxActionSync(
      DbAccountRepository accountRepository,
      EmailRepository emailRepository,
      Clock clock,
      long syncPeriodInSeconds,
      long retryDelaySeconds) {
    this.accountRepository = accountRepository;
    this.emailRepository = emailRepository;
    this.clock = clock;
    this.syncPeriodInSeconds = syncPeriodInSeconds;
    this.retryDelaySeconds = retryDelaySeconds;
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(
        this::syncDueActions, INITIAL_DELAY_SECONDS, syncPeriodInSeconds, TimeUnit.SECONDS);
  }

  public void syncNow() {
    syncDueActions();
  }

  public void stop() {
    scheduler.shutdown();
  }

  void syncDueActions() {
    ZonedDateTime now = ZonedDateTime.now(clock);
    List<MailboxActionQueueRow> actions = emailRepository.claimDueMailboxActions(now, BATCH_SIZE);
    for (MailboxActionQueueRow action : actions) {
      try {
        applyAction(action);
        emailRepository.markMailboxActionSucceeded(action.id(), ZonedDateTime.now(clock));
      } catch (MailboxActionConflictException e) {
        emailRepository.markMailboxActionConflict(
            action.id(), abbreviateError(e), ZonedDateTime.now(clock));
      } catch (MessagingException | RuntimeException e) {
        LOGGER.warn("Failed to sync mailbox action {}", action.id(), e);
        ZonedDateTime retryAt =
            ZonedDateTime.now(clock).plusSeconds(backoffSeconds(action.attemptCount()));
        emailRepository.markMailboxActionRetry(
            action.id(), abbreviateError(e), retryAt, ZonedDateTime.now(clock));
      }
    }
  }

  private void applyAction(MailboxActionQueueRow action) throws MessagingException {
    if (!"MARK_READ".equals(action.actionType()) && !"MARK_UNREAD".equals(action.actionType())) {
      throw new IllegalArgumentException("Unsupported mailbox action type " + action.actionType());
    }
    if (action.sourceRemoteName() == null || action.sourceUid() == null) {
      throw new MailboxActionConflictException("Queued action is missing source mailbox identity");
    }
    Account account = accountRepository.getAccount(action.accountId());
    try (Store store = createConnectedStore(account)) {
      Folder folder = store.getFolder(action.sourceRemoteName());
      if (!(folder instanceof IMAPFolder imapFolder)) {
        throw new MessagingException("Source folder is not an IMAP folder");
      }
      imapFolder.open(Folder.READ_WRITE);
      try {
        if (action.sourceUidValidity() != null
            && imapFolder.getUIDValidity() != action.sourceUidValidity()) {
          throw new MailboxActionConflictException("Source folder UIDVALIDITY changed");
        }
        Message message = imapFolder.getMessageByUID(action.sourceUid());
        if (message == null) {
          throw new MailboxActionConflictException("Source UID no longer exists");
        }
        message.setFlag(Flags.Flag.SEEN, "MARK_READ".equals(action.actionType()));
      } finally {
        imapFolder.close(false);
      }
    }
  }

  private Store createConnectedStore(Account account) throws MessagingException {
    Store store = createStore();
    store.connect(
        account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
    return store;
  }

  Store createStore() {
    try {
      return Session.getInstance(new Properties(), null).getStore("imap");
    } catch (NoSuchProviderException e) {
      throw new IllegalStateException("There is no imap provider, this should not happen.");
    }
  }

  private long backoffSeconds(int attemptCount) {
    return retryDelaySeconds * (1L << Math.max(0, attemptCount));
  }

  private static String abbreviateError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    String collapsed = message.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 237) + "...";
  }

  private static class MailboxActionConflictException extends MessagingException {
    MailboxActionConflictException(String message) {
      super(message);
    }
  }
}
