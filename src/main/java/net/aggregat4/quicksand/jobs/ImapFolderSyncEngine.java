package net.aggregat4.quicksand.jobs;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.jobs.CondstoreSyncPolicy.SyncMode;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ImapFolderSyncEngine {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImapFolderSyncEngine.class);

  private ImapFolderSyncEngine() {}

  static NamedFolder syncFolder(
      int accountId,
      NamedFolder localFolder,
      IMAPFolder remoteFolder,
      Store store,
      FolderRepository folderRepository,
      EmailRepository messageRepository)
      throws MessagingException {
    ImapFolderAccess access = new AngusImapFolderAccess(remoteFolder);
    try {
      return syncFolder(
          accountId,
          localFolder,
          access,
          CondstoreSyncPolicy.supportsCondstore(store),
          folderRepository,
          messageRepository);
    } finally {
      access.close();
    }
  }

  static NamedFolder syncFolder(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      Store store,
      FolderRepository folderRepository,
      EmailRepository messageRepository)
      throws MessagingException {
    return syncFolder(
        accountId,
        localFolder,
        access,
        CondstoreSyncPolicy.supportsCondstore(store),
        folderRepository,
        messageRepository);
  }

  static NamedFolder syncFolder(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      boolean condstoreSupported,
      FolderRepository folderRepository,
      EmailRepository messageRepository)
      throws MessagingException {
    access.openReadOnly(condstoreSupported);
    LOGGER.debug(
        "Opened remote folder {} with {} messages (condstore={})",
        access.getFullName(),
        access.getMessages().length,
        condstoreSupported);

    long remoteUidValidity = access.getUidValidity();
    if (CondstoreSyncPolicy.uidValidityChanged(localFolder.uidValidity(), remoteUidValidity)) {
      LOGGER.info(
          "UIDVALIDITY changed for folder {} ({} -> {}), clearing local mirror",
          access.getFullName(),
          localFolder.uidValidity(),
          remoteUidValidity);
      messageRepository.removeAllByUid(messageRepository.getAllMessageIds(localFolder.id()));
      localFolder = folderRepository.updateSyncCheckpoint(localFolder, null, null);
    }

    localFolder =
        folderRepository.updateRemoteMetadata(
            localFolder, access.getFullName(), localFolder.specialUse(), remoteUidValidity);

    long nowEpochS = Instant.now().getEpochSecond();
    SyncMode syncMode =
        CondstoreSyncPolicy.resolve(
            condstoreSupported,
            localFolder.highestModSeq(),
            nowEpochS,
            localFolder.lastFullSyncEpochS(),
            CondstoreSyncPolicy.DEFAULT_FULL_RECONCILE_INTERVAL_SECONDS);

    long serverHighestModSeq = condstoreSupported ? access.getHighestModSeq() : -1L;
    Long checkpointModSeq = condstoreSupported ? serverHighestModSeq : null;
    if (syncMode == SyncMode.INCREMENTAL) {
      LOGGER.debug(
          "Running CONDSTORE incremental sync for folder {} since modseq {}",
          access.getFullName(),
          localFolder.highestModSeq());
      condstoreIncrementalSync(accountId, localFolder, access, messageRepository);
      return folderRepository.updateSyncCheckpoint(
          localFolder, checkpointModSeq, localFolder.lastFullSyncEpochS());
    }

    LOGGER.debug("Running full folder sync for folder {}", access.getFullName());
    naiveFolderSync(accountId, localFolder, access, messageRepository);
    return folderRepository.updateSyncCheckpoint(localFolder, checkpointModSeq, nowEpochS);
  }

  private static void condstoreIncrementalSync(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      EmailRepository messageRepository)
      throws MessagingException {
    long storedModSeq = localFolder.highestModSeq();
    Set<Long> pendingMoveLikeSourceUids =
        messageRepository.getPendingMoveLikeActionSourceUids(
            accountId, localFolder.remoteName(), localFolder.uidValidity());

    Message[] changedMessages = access.getMessagesByUIDChangedSince(storedModSeq);
    FetchProfile changedProfile = new FetchProfile();
    changedProfile.add(FetchProfile.Item.FLAGS);
    changedProfile.add(UIDFolder.FetchProfileItem.UID);
    if (changedMessages.length > 0) {
      access.fetch(changedMessages, changedProfile);
    }

    Set<Long> remoteUids = new HashSet<>();
    List<Message> messagesToDownload = new ArrayList<>();
    for (Message message : changedMessages) {
      long uid = access.getUid(message);
      remoteUids.add(uid);
      Optional<net.aggregat4.quicksand.domain.Email> existingMessage =
          messageRepository.findByMessageUid(uid);
      if (existingMessage.isPresent()) {
        updateFlagsIfNeeded(messageRepository, existingMessage.get(), message.getFlags());
      } else if (!pendingMoveLikeSourceUids.contains(uid)) {
        messagesToDownload.add(message);
      }
    }

    collectRemoteUids(access, remoteUids);
    deleteExpungedMessages(localFolder, messageRepository, remoteUids);
    downloadNewMessages(localFolder, access, messageRepository, messagesToDownload);
  }

  private static void naiveFolderSync(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      EmailRepository messageRepository)
      throws MessagingException {
    Set<Long> remoteUids = new HashSet<>();
    Set<Long> pendingMoveLikeSourceUids =
        messageRepository.getPendingMoveLikeActionSourceUids(
            accountId, localFolder.remoteName(), localFolder.uidValidity());
    List<Message> messagesToDownload =
        updateLocalMessages(access, messageRepository, remoteUids, pendingMoveLikeSourceUids);
    deleteExpungedMessages(localFolder, messageRepository, remoteUids);
    downloadNewMessages(localFolder, access, messageRepository, messagesToDownload);
  }

  private static List<Message> updateLocalMessages(
      ImapFolderAccess access,
      EmailRepository messageRepository,
      Set<Long> remoteUids,
      Set<Long> pendingMoveLikeSourceUids)
      throws MessagingException {
    Message[] remoteMessages = access.getMessages();
    FetchProfile fetchProfile = new FetchProfile();
    fetchProfile.add(FetchProfile.Item.FLAGS);
    fetchProfile.add(UIDFolder.FetchProfileItem.UID);
    access.fetch(remoteMessages, fetchProfile);

    List<Message> messagesToDownload = new ArrayList<>();
    for (Message message : remoteMessages) {
      long uid = access.getUid(message);
      remoteUids.add(uid);
      Optional<net.aggregat4.quicksand.domain.Email> existingMessage =
          messageRepository.findByMessageUid(uid);
      if (existingMessage.isPresent()) {
        updateFlagsIfNeeded(messageRepository, existingMessage.get(), message.getFlags());
      } else if (!pendingMoveLikeSourceUids.contains(uid)) {
        messagesToDownload.add(message);
      }
    }
    return messagesToDownload;
  }

  private static void collectRemoteUids(ImapFolderAccess access, Set<Long> remoteUids)
      throws MessagingException {
    Message[] remoteMessages = access.getMessages();
    FetchProfile fetchProfile = new FetchProfile();
    fetchProfile.add(UIDFolder.FetchProfileItem.UID);
    access.fetch(remoteMessages, fetchProfile);
    for (Message message : remoteMessages) {
      remoteUids.add(access.getUid(message));
    }
  }

  private static void updateFlagsIfNeeded(
      EmailRepository messageRepository,
      net.aggregat4.quicksand.domain.Email localMessage,
      Flags flags) {
    boolean imapMessageStarred = flags.contains(Flags.Flag.FLAGGED);
    boolean imapMessageRead = flags.contains(Flags.Flag.SEEN);
    boolean localMessageNeedsUpdate =
        (imapMessageRead != localMessage.header().read())
            || (imapMessageStarred != localMessage.header().starred());
    if (localMessageNeedsUpdate) {
      messageRepository.updateFlags(
          localMessage.header().id(), imapMessageStarred, imapMessageRead);
    }
  }

  private static void deleteExpungedMessages(
      NamedFolder localFolder, EmailRepository emailRepository, Set<Long> remoteUids) {
    Set<Long> localUids = emailRepository.getAllMessageIds(localFolder.id());
    localUids.removeAll(remoteUids);
    emailRepository.removeAllByUid(localUids);
  }

  private static void downloadNewMessages(
      NamedFolder localFolder,
      ImapFolderAccess access,
      EmailRepository emailRepository,
      List<Message> messagesToDownload)
      throws MessagingException {
    ImapStoreSync.downloadNewMessages(localFolder, access, emailRepository, messagesToDownload);
  }
}
