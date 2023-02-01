package net.aggregat4.quicksand.jobs;

import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.AccountRepository;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MailFetcher {

    private static final long INITIAL_DELAY_SECONDS = 0;
    private final long fetchPeriodInSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AccountRepository accountRepository;

    private final ConcurrentHashMap<Account, Store> accountStores = new ConcurrentHashMap<>();

    public MailFetcher(AccountRepository accountRepository, long fetchPeriodInSeconds) {
        this.accountRepository = accountRepository;
        this.fetchPeriodInSeconds = fetchPeriodInSeconds;
    }

    public void start() {
        this.scheduler.scheduleWithFixedDelay(this::fetch, INITIAL_DELAY_SECONDS, fetchPeriodInSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        this.scheduler.shutdown();
    }

    private void fetch() {
        System.out.println("fetching");
        checkAndInitializeStores();
        for (Map.Entry<Account, Store> entry : accountStores.entrySet()) {
            Store store = entry.getValue();
            Account account = entry.getKey();
            if (!store.isConnected()) {
                try {
                    store.connect(account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
                } catch (MessagingException e) {
                    // TODO: log this to our yet to be developed logging system
                    e.printStackTrace();
                }
                System.out.println("New connection");
            }
            System.out.println("connected");
            // first sync all folders: add non existent and remove any non present ones (also removing emails)
            // then sync all emails in all folders
            // how do we efficiently track changes? we could use the UIDVALIDITY and UIDNEXT values
            // but we would need to store them somewhere. We could also use the modseq value, but that
            // is only available for IMAP servers that support CONDSTORE. We could also use the
            // LAST-APPEND-UID value, but that is only available for IMAP servers that support
            // QRESYNC. We could also use the HIGHESTMODSEQ value, but that is only available for
            // IMAP servers that support CONDSTORE. We could also use the UIDNEXT value, but that
            // is only available for IMAP servers that support QRESYNC. We could also use the
            // UIDVALIDITY value, but that is only available for IMAP servers that support QRESYNC.

            // See https://www.rfc-editor.org/rfc/rfc4549#section-3 for a recommendation on how to sync a disconnected IMAP client
            // we can skip the client actions for now and try the server to client sync first
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

    private Store createStore(Account account) {
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
