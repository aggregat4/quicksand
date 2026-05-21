package net.aggregat4.quicksand.jobs;

import jakarta.mail.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.*;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.service.AccountFolderMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailFetcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(MailFetcher.class);

  private final long fetchPeriodInSeconds;
  private final boolean idleEnabled;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final DbAccountRepository accountRepository;
  private final FolderRepository folderRepository;
  private final EmailRepository messageRepository;
  private final AccountFolderMappingService accountFolderMappingService;

  private final ConcurrentHashMap<Account, Store> accountStores = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Account, ImapIdleWatcher> idleWatchers =
      new ConcurrentHashMap<>();

  public MailFetcher(
      DbAccountRepository accountRepository,
      long fetchPeriodInSeconds,
      FolderRepository folderRepository,
      EmailRepository emailRepository,
      AccountFolderMappingService accountFolderMappingService,
      boolean idleEnabled) {
    this.accountRepository = accountRepository;
    this.fetchPeriodInSeconds = fetchPeriodInSeconds;
    this.folderRepository = folderRepository;
    this.messageRepository = emailRepository;
    this.accountFolderMappingService = accountFolderMappingService;
    this.idleEnabled = idleEnabled;
  }

  public void start() {
    this.scheduler.scheduleWithFixedDelay(
        this::fetch, fetchPeriodInSeconds, fetchPeriodInSeconds, TimeUnit.SECONDS);
  }

  public void fetchNow() {
    fetch();
  }

  public void stop() {
    this.scheduler.shutdown();
    idleWatchers.values().forEach(ImapIdleWatcher::stop);
    idleWatchers.clear();
  }

  private void ensureIdleWatch(Account account, Store store) {
    if (!idleEnabled || idleWatchers.containsKey(account)) {
      return;
    }
    try {
      if (!ImapIdleWatcher.supportsIdle(store)) {
        LOGGER.debug("IMAP IDLE unavailable for account {}", account.name());
        return;
      }
      ImapIdleWatcher watcher = new ImapIdleWatcher(folderRepository, this::fetchNow);
      watcher.refreshForAccount(account);
      idleWatchers.put(account, watcher);
    } catch (MessagingException e) {
      LOGGER.warn("Failed to start IMAP IDLE for account {}", account.name(), e);
    }
  }

  /** Package private for testing. */
  void fetch() {
    long fetchStarted = System.nanoTime();
    LOGGER.debug("Starting mail fetch for configured accounts");
    checkAndInitializeStores();
    for (Map.Entry<Account, Store> entry : accountStores.entrySet()) {
      Store store = entry.getValue();
      Account account = entry.getKey();
      if (!store.isConnected()) {
        try {
          store.connect(
              account.imapHost(),
              account.imapPort(),
              account.imapUsername(),
              account.imapPassword());
        } catch (MessagingException e) {
          LOGGER.warn("Failed to connect to IMAP store for account {}", account.name(), e);
          continue;
        }
        LOGGER.debug("Opened IMAP connection for account {}", account.name());
      }
      long accountFetchStarted = System.nanoTime();
      ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
      accountFolderMappingService.syncMappingsAfterFolderDiscovery(account.id());
      ensureIdleWatch(account, store);
      LOGGER.debug(
          "Finished mail fetch for account {} in {} ms",
          account.name(),
          elapsedMillis(accountFetchStarted));
    }
    LOGGER.debug("Finished mail fetch in {} ms", elapsedMillis(fetchStarted));
  }

  /** Checks whether we have an active session for each account and if not creates a new session. */
  private void checkAndInitializeStores() {
    List<Account> accounts = accountRepository.getAccounts();
    for (Account account : accounts) {
      accountStores.computeIfAbsent(account, this::createStore);
    }
  }

  /** package private for test */
  Store createStore(Account account) {
    Session session = Session.getInstance(JakartaMailSessionProperties.imap(account), null);
    try {
      return session.getStore("imap");
    } catch (NoSuchProviderException e) {
      throw new IllegalStateException("There is no imap provider, this should not happen.");
    }
  }

  public void invalidateSessions() {
    this.accountStores.clear();
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
