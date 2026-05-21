package net.aggregat4.quicksand.jobs;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.aggregat4.quicksand.domain.Account;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.IdleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Watches one mailbox folder with IMAP IDLE and triggers sync wake-ups. */
final class ImapIdleMonitor implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImapIdleMonitor.class);

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Runnable onNewMail;

  private Store store;
  private IdleManager idleManager;
  private Folder watchedFolder;

  ImapIdleMonitor(Runnable onNewMail) {
    this.onNewMail = onNewMail;
  }

  void start(Account account, String remoteFolderName) throws MessagingException {
    Properties properties = JakartaMailSessionProperties.imap(account);
    properties.put("mail.imap.usesocketchannels", "true");

    Session session = Session.getInstance(properties, null);
    store = session.getStore("imap");
    store.connect(
        account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());

    if (!(store instanceof IMAPStore imapStore) || !imapStore.hasCapability("IDLE")) {
      throw new MessagingException("IMAP IDLE is not supported for account " + account.name());
    }

    try {
      idleManager = new IdleManager(session, executor);
    } catch (IOException e) {
      throw new MessagingException("Failed to initialize IMAP IDLE manager", e);
    }
    watchedFolder = store.getFolder(remoteFolderName);
    watchedFolder.open(Folder.READ_WRITE);
    watchedFolder.addMessageCountListener(
        new MessageCountAdapter() {
          @Override
          public void messagesAdded(MessageCountEvent event) {
            LOGGER.debug(
                "IDLE reported {} new message(s) in {}",
                event.getMessages().length,
                remoteFolderName);
            onNewMail.run();
            try {
              idleManager.watch(watchedFolder);
            } catch (MessagingException e) {
              LOGGER.warn("Failed to resume IDLE watch for folder {}", remoteFolderName, e);
            }
          }
        });
    idleManager.watch(watchedFolder);
    LOGGER.info("Started IMAP IDLE watch on {} for account {}", remoteFolderName, account.name());
  }

  @Override
  public void close() {
    try {
      if (watchedFolder != null && watchedFolder.isOpen()) {
        watchedFolder.close(false);
      }
    } catch (MessagingException e) {
      LOGGER.debug("Failed to close watched IMAP folder", e);
    }
    if (idleManager != null) {
      idleManager.stop();
    }
    try {
      if (store != null && store.isConnected()) {
        store.close();
      }
    } catch (MessagingException e) {
      LOGGER.debug("Failed to close IDLE IMAP store", e);
    }
    executor.shutdownNow();
  }
}
