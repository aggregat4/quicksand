package net.aggregat4.quicksand.jobs;

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

// See https://www.rfc-editor.org/rfc/rfc4549#section-3 for a recommendation on how to sync a
// disconnected IMAP client
// we can skip the client actions for now and try the server to client sync first
public class ImapStoreSync {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImapStoreSync.class);

  public static void syncImapFolders(
      Account account,
      Store store,
      FolderRepository folderRepository,
      EmailRepository messageRepository) {
    long syncStarted = System.nanoTime();
    try {
      // we filter by '*' as that seems to indicate that we want all folders not just toplevel
      // folders
      long listFoldersStarted = System.nanoTime();
      Folder[] folders = store.getDefaultFolder().list("*");
      LOGGER.info(
          "Listed {} remote folders for account {} in {} ms",
          folders.length,
          account.name(),
          elapsedMillis(listFoldersStarted));
      Set<NamedFolder> seenFolders = new HashSet<>();
      List<NamedFolder> localFolders = folderRepository.getFolders(account.id());
      for (Folder folder : folders) {
        // This filter is from https://stackoverflow.com/a/4801728/1996
        // unsure whether we will need it
        if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
          NamedFolder localFolder =
              localFolders.stream()
                  .filter(f -> f.name().equals(folder.getFullName()))
                  .findFirst()
                  .orElseGet(() -> folderRepository.createFolder(account, folder.getFullName()));
          seenFolders.add(localFolder);
          long folderSyncStarted = System.nanoTime();
          syncImapFolder(localFolder, folder, messageRepository);
          LOGGER.info(
              "Synced folder {} for account {} in {} ms",
              folder.getFullName(),
              account.name(),
              elapsedMillis(folderSyncStarted));
        }
      }
      // remove any folders that are not present on the server
      localFolders.stream()
          .filter(f -> !seenFolders.contains(f))
          .forEach(folderRepository::deleteFolder);
      LOGGER.info(
          "Synced all folders for account {} in {} ms", account.name(), elapsedMillis(syncStarted));
    } catch (MessagingException e) {
      // TODO: proper error handling
      throw new RuntimeException(e);
    }
  }

  /**
   * There are multiple ways to synchronize an imap folder: - The naive way is to get all message
   * UIDs and flags from the server and then check which ones we need to locally remove, update the
   * flags of and which ones to download - There are more efficient ways to sync using QRESYNC and
   * CONDSTORE that we definitely need to implement
   */
  private static void syncImapFolder(
      NamedFolder localFolder, Folder remoteFolder, EmailRepository messageRepository)
      throws MessagingException {
    if (!(remoteFolder instanceof IMAPFolder imapFolder)) {
      throw new IllegalStateException(
          "Expected IMAPFolder but got " + remoteFolder.getClass().getName());
    }
    long openStarted = System.nanoTime();
    imapFolder.open(Folder.READ_ONLY);
    LOGGER.info(
        "Opened remote folder {} with {} messages in {} ms",
        imapFolder.getFullName(),
        imapFolder.getMessageCount(),
        elapsedMillis(openStarted));
    naiveFolderSync(localFolder, imapFolder, messageRepository);
    //        try {
    //            // TODO: purge deleted messages
    //
    ////            // get new messages
    ////            Message[] messagesByUID = uidFolder.getMessagesByUID(localFolder.lastSeenUid(),
    // UIDFolder.LASTUID);
    ////            System.out.println("Found " + messagesByUID.length + " new messages");
    ////            for (Message message : messagesByUID) {
    //////                System.out.println("Message: " + message.getSubject());
    //////                messageRepositor
    ////            }
    //        } finally {
    //            imapFolder.close();
    //        }
  }

  private static void naiveFolderSync(
      NamedFolder localFolder, IMAPFolder imapFolder, EmailRepository messageRepository)
      throws MessagingException {
    // TODO: verify that all messages already have their UID set since we use that below
    Set<Long> remoteUids = new HashSet<>();
    long updateStarted = System.nanoTime();
    List<IMAPMessage> messagesToDownload =
        updateLocalMessages(imapFolder, messageRepository, remoteUids);
    LOGGER.info(
        "Checked {} remote messages in folder {}; {} new messages need download; took {} ms",
        remoteUids.size(),
        imapFolder.getFullName(),
        messagesToDownload.size(),
        elapsedMillis(updateStarted));
    long deleteStarted = System.nanoTime();
    deleteExpungedMessages(localFolder, messageRepository, remoteUids);
    LOGGER.info(
        "Deleted expunged local messages for folder {} in {} ms",
        imapFolder.getFullName(),
        elapsedMillis(deleteStarted));
    long downloadStarted = System.nanoTime();
    downloadNewMessages(localFolder, imapFolder, messageRepository, messagesToDownload);
    LOGGER.info(
        "Downloaded {} new messages for folder {} in {} ms",
        messagesToDownload.size(),
        imapFolder.getFullName(),
        elapsedMillis(downloadStarted));
  }

  private static List<IMAPMessage> updateLocalMessages(
      IMAPFolder imapFolder, EmailRepository messageRepository, Set<Long> remoteUids)
      throws MessagingException {
    long getMessagesStarted = System.nanoTime();
    var remoteMessages = imapFolder.getMessages();
    LOGGER.info(
        "Loaded {} remote message handles for folder {} in {} ms",
        remoteMessages.length,
        imapFolder.getFullName(),
        elapsedMillis(getMessagesStarted));
    FetchProfile fp = new FetchProfile();
    fp.add(FetchProfile.Item.FLAGS);
    fp.add(UIDFolder.FetchProfileItem.UID);
    long fetchMetadataStarted = System.nanoTime();
    imapFolder.fetch(remoteMessages, fp);
    LOGGER.info(
        "Fetched flags and UIDs for {} messages in folder {} in {} ms",
        remoteMessages.length,
        imapFolder.getFullName(),
        elapsedMillis(fetchMetadataStarted));
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
        boolean localMessageNeedsUpdate =
            (imapMessageRead != localMessage.header().read())
                || (imapMessageStarred != localMessage.header().starred());
        if (localMessageNeedsUpdate) {
          messageRepository.updateFlags(
              localMessage.header().id(), imapMessageStarred, imapMessageRead);
        }
      } else {
        // Add new messages to our list of messages to download
        messagesToDownload.add(imapMessage);
      }
    }
    return messagesToDownload;
  }

  private static void deleteExpungedMessages(
      NamedFolder localFolder, EmailRepository emailRepository, Set<Long> remoteUids) {
    Set<Long> localUids = emailRepository.getAllMessageIds(localFolder.id());
    localUids.removeAll(remoteUids);
    emailRepository.removeAllByUid(localUids);
  }

  private static void downloadNewMessages(
      NamedFolder localFolder,
      IMAPFolder imapFolder,
      EmailRepository emailRepository,
      List<IMAPMessage> messagesToDownload)
      throws MessagingException {
    FetchProfile newMessageProfile = new FetchProfile();
    newMessageProfile.add(FetchProfile.Item.ENVELOPE);
    newMessageProfile.add(FetchProfile.Item.CONTENT_INFO);
    newMessageProfile.add(UIDFolder.FetchProfileItem.UID);
    IMAPMessage[] messageToDownloadArray = messagesToDownload.toArray(new IMAPMessage[0]);
    long fetchNewMetadataStarted = System.nanoTime();
    imapFolder.fetch(messageToDownloadArray, newMessageProfile);
    LOGGER.info(
        "Fetched envelope/content metadata for {} new messages in folder {} in {} ms",
        messagesToDownload.size(),
        imapFolder.getFullName(),
        elapsedMillis(fetchNewMetadataStarted));
    long extractBodiesStarted = System.nanoTime();
    Map<IMAPMessage, ImapBodyExtractor.StoredBody> storedBodies =
        ImapBodyExtractor.extractBodies(imapFolder, messagesToDownload);
    LOGGER.info(
        "Extracted selected bodies for {} new messages in folder {} in {} ms",
        messagesToDownload.size(),
        imapFolder.getFullName(),
        elapsedMillis(extractBodiesStarted));
    List<Email> newEmails = new ArrayList<>();
    int downloadedCount = 0;
    for (IMAPMessage newMessage : messagesToDownload) {
      List<Actor> actors = getActorsForImapMessage(newMessage);
      addSenderIfPresent(newMessage, actors);
      ZonedDateTime sentDateTime =
          ZonedDateTime.ofInstant(newMessage.getSentDate().toInstant(), ZoneId.systemDefault());
      ZonedDateTime receivedDateTime =
          ZonedDateTime.ofInstant(newMessage.getReceivedDate().toInstant(), ZoneId.systemDefault());
      ImapBodyExtractor.StoredBody storedBody = storedBodies.get(newMessage);
      Email newEmail =
          new Email(
              new EmailHeader(
                  -1,
                  imapFolder.getUID(newMessage),
                  actors,
                  newMessage.getSubject(),
                  sentDateTime,
                  sentDateTime.toEpochSecond(),
                  receivedDateTime,
                  receivedDateTime.toEpochSecond(),
                  storedBody.excerpt(),
                  newMessage.getFlags().contains(Flags.Flag.FLAGGED),
                  false /* TODO attachment handling */,
                  newMessage.getFlags().contains(Flags.Flag.SEEN)),
              storedBody.plainText(),
              storedBody.body(),
              Collections.emptyList() /* TODO attachment handling */);
      newEmails.add(newEmail);
      downloadedCount++;
      if (downloadedCount % 50 == 0 || downloadedCount == messagesToDownload.size()) {
        LOGGER.info(
            "Prepared {}/{} new messages for folder {}",
            downloadedCount,
            messagesToDownload.size(),
            imapFolder.getFullName());
      }
    }
    long storeMessagesStarted = System.nanoTime();
    emailRepository.addMessages(localFolder.id(), newEmails);
    LOGGER.info(
        "Stored {} new messages for folder {} in {} ms",
        newEmails.size(),
        imapFolder.getFullName(),
        elapsedMillis(storeMessagesStarted));
  }

  private static void addSenderIfPresent(IMAPMessage message, List<Actor> actors)
      throws MessagingException {
    var sender = message.getSender();
    if (sender instanceof InternetAddress internetAddress) {
      actors.addAll(
          mapRecipientsToActors(new InternetAddress[] {internetAddress}, ActorType.SENDER));
      return;
    }

    InternetAddress[] fromAddresses = toInternetAddresses(message.getFrom());
    if (fromAddresses.length > 0) {
      actors.addAll(mapRecipientsToActors(fromAddresses, ActorType.SENDER));
    }
  }

  private static List<Actor> getActorsForImapMessage(IMAPMessage msg) throws MessagingException {
    InternetAddress[] toRecipients =
        (InternetAddress[]) msg.getRecipients(Message.RecipientType.TO);
    List<Actor> actors = new ArrayList<>();
    if (toRecipients != null) {
      actors.addAll(mapRecipientsToActors(toRecipients, ActorType.TO));
    }
    InternetAddress[] ccRecipients =
        (InternetAddress[]) msg.getRecipients(Message.RecipientType.CC);
    if (ccRecipients != null) {
      actors.addAll(mapRecipientsToActors(ccRecipients, ActorType.CC));
    }
    InternetAddress[] bccRecipients =
        (InternetAddress[]) msg.getRecipients(Message.RecipientType.BCC);
    if (bccRecipients != null) {
      actors.addAll(mapRecipientsToActors(bccRecipients, ActorType.BCC));
    }
    return actors;
  }

  private static List<Actor> mapRecipientsToActors(InternetAddress[] recipients, ActorType type) {
    List<Actor> actors = new ArrayList<>();
    for (InternetAddress recipient : recipients) {
      actors.add(
          new Actor(type, recipient.getAddress(), Optional.ofNullable(recipient.getPersonal())));
    }
    return actors;
  }

  private static InternetAddress[] toInternetAddresses(Address[] addresses) {
    if (addresses == null) {
      return new InternetAddress[0];
    }
    return java.util.Arrays.stream(addresses)
        .filter(InternetAddress.class::isInstance)
        .map(InternetAddress.class::cast)
        .toArray(InternetAddress[]::new);
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
