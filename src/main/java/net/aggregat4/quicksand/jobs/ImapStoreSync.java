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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
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
      LOGGER.debug(
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
          String remoteName = folder.getFullName();
          FolderSpecialUse remoteSpecialUse = specialUseFor(folder).orElse(null);
          NamedFolder localFolder =
              localFolders.stream()
                  .filter(f -> matchesRemoteFolder(f, remoteName))
                  .findFirst()
                  .orElseGet(
                      () ->
                          folderRepository.createFolder(
                              account, remoteName, remoteName, remoteSpecialUse, null));
          localFolder =
              folderRepository.updateRemoteMetadata(
                  localFolder,
                  remoteName,
                  resolveSpecialUse(localFolder, remoteSpecialUse),
                  localFolder.uidValidity());
          seenFolders.add(localFolder);
          long folderSyncStarted = System.nanoTime();
          localFolder =
              ImapFolderSyncEngine.syncFolder(
                  account.id(),
                  localFolder,
                  (IMAPFolder) folder,
                  store,
                  folderRepository,
                  messageRepository);
          seenFolders.remove(localFolder);
          seenFolders.add(localFolder);
          LOGGER.debug(
              "Synced folder {} for account {} in {} ms",
              remoteName,
              account.name(),
              elapsedMillis(folderSyncStarted));
        }
      }
      // remove any folders that are not present on the server
      localFolders.stream()
          .filter(f -> !seenFolders.contains(f))
          .forEach(folderRepository::deleteFolder);
      LOGGER.debug(
          "Synced all folders for account {} in {} ms", account.name(), elapsedMillis(syncStarted));
    } catch (MessagingException e) {
      // TODO: proper error handling
      throw new RuntimeException(e);
    }
  }

  static void downloadNewMessages(
      NamedFolder localFolder,
      ImapFolderAccess imapFolder,
      EmailRepository emailRepository,
      List<Message> messagesToDownload)
      throws MessagingException {
    if (messagesToDownload.isEmpty()) {
      return;
    }
    List<IMAPMessage> imapMessages = new ArrayList<>();
    for (Message message : messagesToDownload) {
      if (message instanceof IMAPMessage imapMessage) {
        imapMessages.add(imapMessage);
      } else {
        throw new IllegalStateException(
            "Expected IMAPMessage but got " + message.getClass().getName());
      }
    }
    FetchProfile newMessageProfile = new FetchProfile();
    newMessageProfile.add(FetchProfile.Item.ENVELOPE);
    newMessageProfile.add(FetchProfile.Item.CONTENT_INFO);
    newMessageProfile.add(UIDFolder.FetchProfileItem.UID);
    IMAPMessage[] messageToDownloadArray = imapMessages.toArray(new IMAPMessage[0]);
    long fetchNewMetadataStarted = System.nanoTime();
    imapFolder.fetch(messageToDownloadArray, newMessageProfile);
    LOGGER.debug(
        "Fetched envelope/content metadata for {} new messages in folder {} in {} ms",
        imapMessages.size(),
        imapFolder.getFullName(),
        elapsedMillis(fetchNewMetadataStarted));
    long extractBodiesStarted = System.nanoTime();
    Map<IMAPMessage, ImapBodyExtractor.StoredBody> storedBodies =
        ImapBodyExtractor.extractBodies(imapFolder.unwrapImapFolder(), imapMessages);
    LOGGER.debug(
        "Extracted selected bodies for {} new messages in folder {} in {} ms",
        imapMessages.size(),
        imapFolder.getFullName(),
        elapsedMillis(extractBodiesStarted));
    List<Email> newEmails = new ArrayList<>();
    int downloadedCount = 0;
    for (IMAPMessage newMessage : imapMessages) {
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
                  imapFolder.getUid(newMessage),
                  actors,
                  newMessage.getSubject(),
                  sentDateTime,
                  sentDateTime.toEpochSecond(),
                  receivedDateTime,
                  receivedDateTime.toEpochSecond(),
                  storedBody.excerpt(),
                  newMessage.getFlags().contains(Flags.Flag.FLAGGED),
                  false /* TODO attachment handling */,
                  isMessageRead(localFolder, newMessage)),
              storedBody.plainText(),
              storedBody.body(),
              Collections.emptyList() /* TODO attachment handling */);
      newEmails.add(newEmail);
      downloadedCount++;
      if (downloadedCount % 50 == 0 || downloadedCount == imapMessages.size()) {
        LOGGER.debug(
            "Prepared {}/{} new messages for folder {}",
            downloadedCount,
            imapMessages.size(),
            imapFolder.getFullName());
      }
    }
    long storeMessagesStarted = System.nanoTime();
    emailRepository.addMessages(localFolder.id(), newEmails);
    LOGGER.debug(
        "Stored {} new messages for folder {} in {} ms",
        newEmails.size(),
        imapFolder.getFullName(),
        elapsedMillis(storeMessagesStarted));
  }

  private static boolean isMessageRead(NamedFolder localFolder, IMAPMessage newMessage)
      throws MessagingException {
    if (localFolder.specialUse() == FolderSpecialUse.SENT) {
      return true;
    }
    return newMessage.getFlags().contains(Flags.Flag.SEEN);
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

  private static boolean matchesRemoteFolder(NamedFolder localFolder, String remoteName) {
    return remoteName.equals(localFolder.remoteName()) || remoteName.equals(localFolder.name());
  }

  /**
   * IMAP SPECIAL-USE attributes win when present. Servers such as GreenMail often omit them for
   * user-created folders, so preserve locally assigned roles from folder setup.
   */
  static FolderSpecialUse resolveSpecialUse(
      NamedFolder localFolder, FolderSpecialUse remoteSpecialUse) {
    if (remoteSpecialUse != null) {
      return remoteSpecialUse;
    }
    return localFolder.specialUse();
  }

  static Optional<FolderSpecialUse> specialUseFor(Folder folder) throws MessagingException {
    if ("INBOX".equalsIgnoreCase(folder.getFullName())) {
      return Optional.of(FolderSpecialUse.INBOX);
    }
    if (folder instanceof IMAPFolder imapFolder) {
      return specialUseFromAttributes(imapFolder.getAttributes());
    }
    return Optional.empty();
  }

  static Optional<FolderSpecialUse> specialUseFromAttributes(String[] attributes) {
    Set<String> normalizedAttributes = new HashSet<>();
    for (String attribute : attributes) {
      String normalized = attribute.startsWith("\\") ? attribute.substring(1) : attribute;
      normalizedAttributes.add(normalized.toUpperCase(Locale.ROOT));
    }
    for (String specialUse : List.of("ARCHIVE", "TRASH", "JUNK", "SPAM", "SENT", "DRAFTS")) {
      if (normalizedAttributes.contains(specialUse)) {
        return Optional.of(
            "SPAM".equals(specialUse)
                ? FolderSpecialUse.JUNK
                : FolderSpecialUse.valueOf(specialUse));
      }
    }
    return Optional.empty();
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
