package net.aggregat4.quicksand.jobs;

import com.sun.mail.imap.IMAPBodyPart;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import jakarta.mail.*;
import jakarta.mail.Folder;
import jakarta.mail.internet.InternetAddress;
import net.aggregat4.quicksand.domain.*;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.MessageRepository;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MailFetcher {

    private static final long INITIAL_DELAY_SECONDS = 0;
    private final long fetchPeriodInSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AccountRepository accountRepository;
    private final FolderRepository folderRepository;
    private final MessageRepository messageRepository;

    private final ConcurrentHashMap<Account, Store> accountStores = new ConcurrentHashMap<>();

    public MailFetcher(AccountRepository accountRepository, long fetchPeriodInSeconds, FolderRepository folderRepository, MessageRepository messageRepository) {
        this.accountRepository = accountRepository;
        this.fetchPeriodInSeconds = fetchPeriodInSeconds;
        this.folderRepository = folderRepository;
        this.messageRepository = messageRepository;
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

            syncImapFolders(account);

        }
    }

    private void syncImapFolders(Account account) {
        Store store = accountStores.get(account);
        try {
            // we filter by '*' as that seems to indicate that we want all folders not just toplevel folders
            Folder[] folders = store.getDefaultFolder().list("*");
            Set<NamedFolder> seenFolders = new HashSet<>();
            List<NamedFolder> localFolders = folderRepository.getFolders(account);
            for (Folder folder : folders) {
                // This filter is from https://stackoverflow.com/a/4801728/1996
                // unsure whether we will need it
                if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    NamedFolder localFolder = localFolders.stream()
                            .filter(f -> f.name().equals(folder.getName()))
                            .findFirst()
                            .orElseGet(() -> folderRepository.createFolder(account, folder.getName()));
                    seenFolders.add(localFolder);
                    syncImapFolder(localFolder, folder);
                }
            }
            // remove any folders that are not present on the server
            localFolders.stream()
                    .filter(f -> !seenFolders.contains(f))
                    .forEach(folderRepository::deleteFolder);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    /**
     * There are multiple ways to synchronize an imap folder:
     * - The naive way is to get all message UIDs and flags from the server and then check which ones we need to locally remove, update the flags of and which ones to download
     * - There are more efficient ways to sync using QRESYNC and CONDSTORE that we definitely need to implement
     */
    private void syncImapFolder(NamedFolder localFolder, Folder remoteFolder) throws MessagingException {
        assert(remoteFolder instanceof IMAPFolder);
        IMAPFolder imapFolder = (IMAPFolder) remoteFolder;
        imapFolder.open(Folder.READ_ONLY);
        naiveFolderSync(localFolder, imapFolder);
//        try {
//            // TODO: purge deleted messages
//
////            // get new messages
////            Message[] messagesByUID = uidFolder.getMessagesByUID(localFolder.lastSeenUid(), UIDFolder.LASTUID);
////            System.out.println("Found " + messagesByUID.length + " new messages");
////            for (Message message : messagesByUID) {
//////                System.out.println("Message: " + message.getSubject());
//////                messageRepositor
////            }
//        } finally {
//            imapFolder.close();
//        }
    }

    private void naiveFolderSync(NamedFolder localFolder, IMAPFolder imapFolder) throws MessagingException {
        var remoteMessages = imapFolder.getMessages();
        // TODO verify that all messages already have their UID set since we fetch that below
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        imapFolder.fetch(remoteMessages, fp);
        Set<Long> remoteUids = new HashSet<>();
        ArrayList<IMAPMessage> messagesToDownload = new ArrayList<>();
        for (Message msg : remoteMessages) {
            IMAPMessage imapMessage = (IMAPMessage) msg;
            Flags flags = msg.getFlags();
            long uid = imapFolder.getUID(imapMessage);
            remoteUids.add(uid);
            Optional<Email> existingMessage = messageRepository.findByMessageId(uid);
            if (existingMessage.isPresent()) {
                Email localMessage = existingMessage.get();
                boolean imapMessageStarred = flags.contains(Flags.Flag.FLAGGED);
                boolean imapMessageRead = flags.contains(Flags.Flag.SEEN);
                // TODO: check if there are more things to sync for a message
                boolean localMessageNeedsUpdate = (imapMessageRead != localMessage.header().read()) || (imapMessageStarred != localMessage.header().starred());
                if (localMessageNeedsUpdate) {
                    messageRepository.updateFlags(localMessage.header().id(), imapMessageStarred, imapMessageRead);
                }
            } else {
                messagesToDownload.add(imapMessage);
            }
        }
        Set<Long> localUids = messageRepository.getAllMessageIds(localFolder.id());
        localUids.removeAll(remoteUids);
        messageRepository.removeAllByUid(localUids);
        FetchProfile newMessageProfile = new FetchProfile();
        newMessageProfile.add(FetchProfile.Item.ENVELOPE);
        newMessageProfile.add(FetchProfile.Item.CONTENT_INFO);
        IMAPMessage[] messageToDownloadArray = (IMAPMessage[]) messagesToDownload.toArray();
        imapFolder.fetch(messageToDownloadArray, newMessageProfile);
        for (IMAPMessage newMessage : messagesToDownload) {
            InternetAddress sender = (InternetAddress) newMessage.getSender();
            InternetAddress [] toRecipients = (InternetAddress[]) newMessage.getRecipients(Message.RecipientType.TO);
            InternetAddress [] ccRecipients = (InternetAddress[]) newMessage.getRecipients(Message.RecipientType.CC);
            InternetAddress [] bccRecipients = (InternetAddress[]) newMessage.getRecipients(Message.RecipientType.BCC);
            Email newEmail = new Email(
                    new EmailHeader(
                            -1,
                            imapFolder.getUID(newMessage),
                            addressToActor(sender),
                            Arrays.stream(toRecipients).map(MailFetcher::addressToActor).toList(),
                            Arrays.stream(ccRecipients).map(MailFetcher::addressToActor).toList(),
                            Arrays.stream(bccRecipients).map(MailFetcher::addressToActor).toList(),
                            newMessage.getSubject(),
                            ZonedDateTime.ofInstant(newMessage.getSentDate().toInstant(), ZoneId.systemDefault()),
                            ZonedDateTime.ofInstant(newMessage.getReceivedDate().toInstant(), ZoneId.systemDefault()),
                            null /* TODO Body handling */,
                            newMessage.getFlags().contains(Flags.Flag.FLAGGED),
                            false /* TODO attachment handling */,
                            newMessage.getFlags().contains(Flags.Flag.SEEN)
                    ),
                    false /* TODO Body handling */,
                    null /* TODO body handling */,
                    Collections.emptyList() /* TODO attachment handling */
            );
            messageRepository.addMessage(localFolder.id(), newEmail);
        }
    }

    private static Actor addressToActor(InternetAddress address) {
        return new Actor(address.getAddress(), Optional.ofNullable(address.getPersonal()));
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
