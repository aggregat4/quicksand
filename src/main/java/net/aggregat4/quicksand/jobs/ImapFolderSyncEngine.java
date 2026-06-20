package net.aggregat4.quicksand.jobs;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
          CondstoreSyncPolicy.supportsQresync(store),
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
        CondstoreSyncPolicy.supportsQresync(store),
        folderRepository,
        messageRepository);
  }

  static NamedFolder syncFolder(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      boolean condstoreSupported,
      boolean qresyncSupported,
      FolderRepository folderRepository,
      EmailRepository messageRepository)
      throws MessagingException {
    access.openReadOnly(
        ImapSyncOpenParameters.forFolder(localFolder, condstoreSupported, qresyncSupported));
    LOGGER.debug(
        "Opened remote folder {} (condstore={}, qresync={})",
        access.getFullName(),
        condstoreSupported,
        qresyncSupported);

    long remoteUidValidity = access.getUidValidity();
    if (CondstoreSyncPolicy.uidValidityChanged(localFolder.uidValidity(), remoteUidValidity)) {
      LOGGER.info(
          "UIDVALIDITY changed for folder {} ({} -> {}), clearing local mirror",
          access.getFullName(),
          localFolder.uidValidity(),
          remoteUidValidity);
      ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
      messageRepository.markMoveLikeActionsConflictForUidValidityChange(
          accountId, localFolder.id(), remoteUidValidity, now);
      Set<Long> localUids = new HashSet<>(messageRepository.getAllMessageIds(localFolder.id()));
      localUids.removeAll(messageRepository.getMoveLikeProtectedUidsInFolder(localFolder.id()));
      messageRepository.removeAllByUid(localFolder.id(), localUids);
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
      condstoreIncrementalSync(accountId, localFolder, access, messageRepository, qresyncSupported);
      return folderRepository.updateSyncCheckpoint(
          localFolder, checkpointModSeq, localFolder.lastFullSyncEpochS());
    }

    LOGGER.debug("Running full folder sync for folder {}", access.getFullName());
    naiveFolderSync(accountId, localFolder, access, messageRepository);
    return folderRepository.updateSyncCheckpoint(localFolder, checkpointModSeq, nowEpochS);
  }

  private static void resolveSucceededMoveLikeSourcesAbsentFromRemote(
      int accountId,
      NamedFolder localFolder,
      EmailRepository messageRepository,
      Set<Long> remoteUidsPresent) {
    messageRepository.resolveMoveLikeSourceUidsAbsentFromRemote(
        accountId, localFolder.remoteName(), localFolder.uidValidity(), remoteUidsPresent);
  }

  private static void condstoreIncrementalSync(
      int accountId,
      NamedFolder localFolder,
      ImapFolderAccess access,
      EmailRepository messageRepository,
      boolean qresyncSupported)
      throws MessagingException {
    long storedModSeq = localFolder.highestModSeq();
    Set<Long> pendingMoveLikeSourceUids =
        messageRepository.getPendingMoveLikeActionSourceUids(
            accountId, localFolder.remoteName(), localFolder.uidValidity());
    Set<Long> pendingReadStateSourceUids =
        messageRepository.getPendingReadStateActionSourceUids(
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
        updateFlagsIfNeeded(
            messageRepository,
            existingMessage.get(),
            message.getFlags(),
            pendingReadStateSourceUids);
      } else if (!pendingMoveLikeSourceUids.contains(uid)) {
        messagesToDownload.add(message);
      }
    }

    if (qresyncSupported && access.openedWithQresync()) {
      deleteVanishedUids(messageRepository, localFolder.id(), access.getVanishedUids());
    } else {
      collectRemoteUids(access, remoteUids);
      deleteExpungedMessages(localFolder, messageRepository, remoteUids);
      resolveSucceededMoveLikeSourcesAbsentFromRemote(
          accountId, localFolder, messageRepository, remoteUids);
    }
    downloadNewMessages(localFolder, access, messageRepository, messagesToDownload);
  }

  private static void deleteVanishedUids(
      EmailRepository messageRepository, int folderId, long[] vanishedUids) {
    if (vanishedUids.length == 0) {
      return;
    }
    Set<Long> uids = new HashSet<>();
    for (long uid : vanishedUids) {
      uids.add(uid);
    }
    uids.removeAll(messageRepository.getMoveLikeProtectedUidsInFolder(folderId));
    messageRepository.removeAllByUid(folderId, uids);
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
    Set<Long> pendingReadStateSourceUids =
        messageRepository.getPendingReadStateActionSourceUids(
            accountId, localFolder.remoteName(), localFolder.uidValidity());
    List<Message> messagesToDownload =
        updateLocalMessages(
            access,
            messageRepository,
            remoteUids,
            pendingMoveLikeSourceUids,
            pendingReadStateSourceUids);
    deleteExpungedMessages(localFolder, messageRepository, remoteUids);
    resolveSucceededMoveLikeSourcesAbsentFromRemote(
        accountId, localFolder, messageRepository, remoteUids);
    downloadNewMessages(localFolder, access, messageRepository, messagesToDownload);
  }

  private static List<Message> updateLocalMessages(
      ImapFolderAccess access,
      EmailRepository messageRepository,
      Set<Long> remoteUids,
      Set<Long> pendingMoveLikeSourceUids,
      Set<Long> pendingReadStateSourceUids)
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
        updateFlagsIfNeeded(
            messageRepository,
            existingMessage.get(),
            message.getFlags(),
            pendingReadStateSourceUids);
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
      Flags flags,
      Set<Long> pendingReadStateSourceUids) {
    boolean imapMessageStarred = flags.contains(Flags.Flag.FLAGGED);
    boolean imapMessageRead = flags.contains(Flags.Flag.SEEN);
    boolean skipReadSync = pendingReadStateSourceUids.contains(localMessage.header().imapUid());
    boolean localMessageNeedsUpdate =
        (!skipReadSync && imapMessageRead != localMessage.header().read())
            || (imapMessageStarred != localMessage.header().starred());
    if (localMessageNeedsUpdate) {
      boolean readToApply = skipReadSync ? localMessage.header().read() : imapMessageRead;
      messageRepository.updateFlags(localMessage.header().id(), imapMessageStarred, readToApply);
    }
  }

  private static void deleteExpungedMessages(
      NamedFolder localFolder, EmailRepository emailRepository, Set<Long> remoteUids) {
    Set<Long> localUids = new HashSet<>(emailRepository.getAllMessageIds(localFolder.id()));
    localUids.removeAll(remoteUids);
    localUids.removeAll(emailRepository.getMoveLikeProtectedUidsInFolder(localFolder.id()));
    emailRepository.removeAllByUid(localFolder.id(), localUids);
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
