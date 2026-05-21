package net.aggregat4.quicksand.jobs;

import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ImapIdleWatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImapIdleWatcher.class);

  private final FolderRepository folderRepository;
  private final Runnable onNewMail;

  private ImapIdleMonitor monitor;

  ImapIdleWatcher(FolderRepository folderRepository, Runnable onNewMail) {
    this.folderRepository = folderRepository;
    this.onNewMail = onNewMail;
  }

  void refreshForAccount(Account account) {
    stop();
    String remoteFolderName = inboxRemoteName(account.id()).orElse(null);
    if (remoteFolderName == null) {
      LOGGER.debug("Skipping IDLE for account {} until INBOX is discovered", account.name());
      return;
    }
    try {
      monitor = new ImapIdleMonitor(onNewMail);
      monitor.start(account, remoteFolderName);
    } catch (MessagingException e) {
      LOGGER.warn("Failed to start IMAP IDLE for account {}", account.name(), e);
      stop();
    }
  }

  void stop() {
    if (monitor != null) {
      monitor.close();
      monitor = null;
    }
  }

  static boolean supportsIdle(Store store) throws MessagingException {
    return store instanceof org.eclipse.angus.mail.imap.IMAPStore imapStore
        && imapStore.hasCapability("IDLE");
  }

  private java.util.Optional<String> inboxRemoteName(int accountId) {
    return folderRepository.getFolders(accountId).stream()
        .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
        .map(NamedFolder::remoteName)
        .filter(name -> name != null && !name.isBlank())
        .findFirst();
  }
}
