package net.aggregat4.quicksand.jobs;

import jakarta.mail.*;
import net.aggregat4.quicksand.domain.*;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MailFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailFetcher.class);

    private static final long INITIAL_DELAY_SECONDS = 0;
    private final long fetchPeriodInSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final DbAccountRepository accountRepository;
    private final FolderRepository folderRepository;
    private final EmailRepository messageRepository;

    private final ConcurrentHashMap<Account, Store> accountStores = new ConcurrentHashMap<>();

    public MailFetcher(DbAccountRepository accountRepository, long fetchPeriodInSeconds, FolderRepository folderRepository, EmailRepository emailRepository) {
        this.accountRepository = accountRepository;
        this.fetchPeriodInSeconds = fetchPeriodInSeconds;
        this.folderRepository = folderRepository;
        this.messageRepository = emailRepository;
    }

    public void start() {
        this.scheduler.scheduleWithFixedDelay(this::fetch, INITIAL_DELAY_SECONDS, fetchPeriodInSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        this.scheduler.shutdown();
    }

    /**
     * Package private for testing.
     */
    void fetch() {
        LOGGER.debug("Fetching mail for configured accounts");
        checkAndInitializeStores();
        for (Map.Entry<Account, Store> entry : accountStores.entrySet()) {
            Store store = entry.getValue();
            Account account = entry.getKey();
            if (!store.isConnected()) {
                try {
                    store.connect(account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
                } catch (MessagingException e) {
                    LOGGER.warn("Failed to connect to IMAP store for account {}", account.name(), e);
                    continue;
                }
                LOGGER.info("Opened IMAP connection for account {}", account.name());
            }
            ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
        }
    }

    /**
     * Checks whether we have an active session for each account and if not creates a new session.
     */
    private void checkAndInitializeStores() {
        List<Account> accounts = accountRepository.getAccounts();
        for (Account account : accounts) {
            accountStores.computeIfAbsent(account, this::createStore);
        }
    }

    /**
     * package private for test
     */
    Store createStore(Account account) {
        Properties props = new Properties();
        Session session = Session.getInstance(props, null);
        try {
            return session.getStore("imap");
        } catch (NoSuchProviderException e) {
            throw new IllegalStateException("There is no imap provider, this should not happen.");
        }
    }

    public void invalidateSessions() {
        this.accountStores.clear();
    }
}
