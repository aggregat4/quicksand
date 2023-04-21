package net.aggregat4.quicksand.jobs;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;
import net.aggregat4.quicksand.repository.EmailRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
public class ImapStoreSync {
    static void syncImapFolders(Account account, Store store, FolderRepository folderRepository, EmailRepository messageRepository) {
        try {
            // we filter by '*' as that seems to indicate that we want all folders not just toplevel folders
            Folder[] folders = store.getDefaultFolder().list("*");
            Set<NamedFolder> seenFolders = new HashSet<>();
            List<NamedFolder> localFolders = folderRepository.getFolders(account.id());
            for (Folder folder : folders) {
                // This filter is from https://stackoverflow.com/a/4801728/1996
                // unsure whether we will need it
                if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    NamedFolder localFolder = localFolders.stream()
                            .filter(f -> f.name().equals(folder.getName()))
                            .findFirst()
                            .orElseGet(() -> folderRepository.createFolder(account, folder.getName()));
                    seenFolders.add(localFolder);
                    syncImapFolder(localFolder, folder, messageRepository);
                }
            }
            // remove any folders that are not present on the server
            localFolders.stream()
                    .filter(f -> !seenFolders.contains(f))
                    .forEach(folderRepository::deleteFolder);
        } catch (MessagingException e) {
            // TODO: proper error handling
            throw new RuntimeException(e);
        }
    }

    /**
     * There are multiple ways to synchronize an imap folder:
     * - The naive way is to get all message UIDs and flags from the server and then check which ones we need to locally remove, update the flags of and which ones to download
     * - There are more efficient ways to sync using QRESYNC and CONDSTORE that we definitely need to implement
     */
    private static void syncImapFolder(NamedFolder localFolder, Folder remoteFolder, EmailRepository messageRepository) throws MessagingException {
        assert (remoteFolder instanceof IMAPFolder);
        IMAPFolder imapFolder = (IMAPFolder) remoteFolder;
        imapFolder.open(Folder.READ_ONLY);
        naiveFolderSync(localFolder, imapFolder, messageRepository);
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

    private static void naiveFolderSync(NamedFolder localFolder, IMAPFolder imapFolder, EmailRepository messageRepository) throws MessagingException {
        // TODO verify that all messages already have their UID set since we use that below
        Set<Long> remoteUids = new HashSet<>();
        ArrayList<IMAPMessage> messagesToDownload = updateLocalMessages(imapFolder, messageRepository, remoteUids);
        deleteExpungedMessages(localFolder, messageRepository, remoteUids);
        downloadNewMessages(localFolder, imapFolder, messageRepository, messagesToDownload);
    }

    private static ArrayList<IMAPMessage> updateLocalMessages(IMAPFolder imapFolder, EmailRepository messageRepository, Set<Long> remoteUids) throws MessagingException {
        var remoteMessages = imapFolder.getMessages();
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        imapFolder.fetch(remoteMessages, fp);
        ArrayList<IMAPMessage> messagesToDownload = new ArrayList<>();
        for (Message msg : remoteMessages) {
            IMAPMessage imapMessage = (IMAPMessage) msg;
            Flags flags = msg.getFlags();
            long uid = imapFolder.getUID(imapMessage);
            remoteUids.add(uid);
            Optional<Email> existingMessage = messageRepository.findByMessageUid(uid);
            if (existingMessage.isPresent()) {
                Email localMessage = existingMessage.get();
                boolean imapMessageStarred = flags.contains(Flags.Flag.FLAGGED);
                boolean imapMessageRead = flags.contains(Flags.Flag.SEEN);
                // Update existing messages if any metadata was changed
                // TODO: check if there are more things to sync for a message
                boolean localMessageNeedsUpdate = (imapMessageRead != localMessage.header().read()) || (imapMessageStarred != localMessage.header().starred());
                if (localMessageNeedsUpdate) {
                    messageRepository.updateFlags(localMessage.header().id(), imapMessageStarred, imapMessageRead);
                }
            } else {
                // Add new messages to our list of messages to download
                messagesToDownload.add(imapMessage);
            }
        }
        return messagesToDownload;
    }

    private static void deleteExpungedMessages(NamedFolder localFolder, EmailRepository emailRepository, Set<Long> remoteUids) {
        Set<Long> localUids = emailRepository.getAllMessageIds(localFolder.id());
        localUids.removeAll(remoteUids);
        emailRepository.removeAllByUid(localUids);
    }

    private static void downloadNewMessages(NamedFolder localFolder, IMAPFolder imapFolder, EmailRepository emailRepository, ArrayList<IMAPMessage> messagesToDownload) throws MessagingException {
        FetchProfile newMessageProfile = new FetchProfile();
        newMessageProfile.add(FetchProfile.Item.ENVELOPE);
        newMessageProfile.add(FetchProfile.Item.CONTENT_INFO);
        IMAPMessage[] messageToDownloadArray = messagesToDownload.toArray(new IMAPMessage[0]);
        imapFolder.fetch(messageToDownloadArray, newMessageProfile);
        for (IMAPMessage newMessage : messagesToDownload) {
            InternetAddress sender = (InternetAddress) newMessage.getSender();
            List<Actor> actors = getActorsForImapMessage(newMessage);
            actors.addAll(mapRecipientsToActors(new InternetAddress[]{sender}, ActorType.SENDER));
            ZonedDateTime sentDateTime = ZonedDateTime.ofInstant(newMessage.getSentDate().toInstant(), ZoneId.systemDefault());
            ZonedDateTime receivedDateTime = ZonedDateTime.ofInstant(newMessage.getReceivedDate().toInstant(), ZoneId.systemDefault());
            Email newEmail = new Email(
                    new EmailHeader(
                            -1,
                            imapFolder.getUID(newMessage),
                            actors,
                            newMessage.getSubject(),
                            sentDateTime,
                            sentDateTime.toEpochSecond(),
                            receivedDateTime,
                            receivedDateTime.toEpochSecond(),
                            null /* TODO Body handling */,
                            newMessage.getFlags().contains(Flags.Flag.FLAGGED),
                            false /* TODO attachment handling */,
                            newMessage.getFlags().contains(Flags.Flag.SEEN)
                    ),
                    false /* TODO Body handling */,
                    null /* TODO body handling */,
                    Collections.emptyList() /* TODO attachment handling */
            );
            emailRepository.addMessage(localFolder.id(), newEmail);
        }
    }

    private static List<Actor> getActorsForImapMessage(IMAPMessage msg) throws MessagingException {
        InternetAddress[] toRecipients = (InternetAddress[]) msg.getRecipients(Message.RecipientType.TO);
        // NOTE: this assumes that there is always at least one TO recipient, I am not sure that this is an invariant that
        // the API guarantees
        assert(toRecipients != null);
        List<Actor> actors = new ArrayList<>(mapRecipientsToActors(toRecipients, ActorType.TO));
        InternetAddress[] ccRecipients = (InternetAddress[]) msg.getRecipients(Message.RecipientType.CC);
        if (ccRecipients != null) {
            actors.addAll(mapRecipientsToActors(ccRecipients, ActorType.CC));
        }
        InternetAddress[] bccRecipients = (InternetAddress[]) msg.getRecipients(Message.RecipientType.BCC);
        if (bccRecipients != null) {
            actors.addAll(mapRecipientsToActors(bccRecipients, ActorType.BCC));
        }
        return actors;
    }

    private static List<Actor> mapRecipientsToActors(InternetAddress[] recipients, ActorType type) {
        List<Actor> actors = new ArrayList<>();
        for (InternetAddress recipient : recipients) {
            actors.add(new Actor(type, recipient.getAddress(), Optional.ofNullable(recipient.getPersonal())));
        }
        return actors;
    }

}
