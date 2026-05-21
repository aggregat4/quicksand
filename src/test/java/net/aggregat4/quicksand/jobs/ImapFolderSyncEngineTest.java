package net.aggregat4.quicksand.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.Test;

class ImapFolderSyncEngineTest {

  @Test
  void incrementalSyncUpdatesFlagsFromChangedSinceFetch() throws Exception {
    Account account = new Account(1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    TrackingEmailRepository emailRepository = new TrackingEmailRepository();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 42L);
    long lastFullSync = Instant.now().getEpochSecond() - 60;
    inbox = folderRepository.updateSyncCheckpoint(inbox, 100L, lastFullSync);
    emailRepository.seedExisting(1L, inbox.id(), false, false);

    FakeImapFolderAccess access = new FakeImapFolderAccess();
    access.serverHighestModSeq = 150L;
    access.changedMessages =
        List.of(new FakeMessage(1L, new Flags(Flags.Flag.SEEN), "Changed subject"));

    NamedFolder synced =
        ImapFolderSyncEngine.syncFolder(
            account.id(), inbox, access, true, false, folderRepository, emailRepository);

    assertEquals(1, access.changedSinceCalls);
    assertTrue(emailRepository.updatedFlags);
    assertEquals(150L, synced.highestModSeq());
    assertEquals(lastFullSync, synced.lastFullSyncEpochS());
  }

  @Test
  void uidValidityChangeClearsLocalMirrorAndRunsFullSync() throws Exception {
    Account account = new Account(1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    TrackingEmailRepository emailRepository = new TrackingEmailRepository();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 42L);
    inbox = folderRepository.updateSyncCheckpoint(inbox, 100L, Instant.now().getEpochSecond() - 60);
    emailRepository.seedExisting(1L, inbox.id(), false, false);

    FakeImapFolderAccess access = new FakeImapFolderAccess();
    access.uidValidity = 99L;
    access.allMessages = List.of();

    NamedFolder synced =
        ImapFolderSyncEngine.syncFolder(
            account.id(), inbox, access, true, false, folderRepository, emailRepository);

    assertTrue(emailRepository.clearedFolder);
    assertEquals(0, access.changedSinceCalls);
    assertTrue(access.fullSyncRequested);
    assertEquals(99L, synced.uidValidity());
    assertEquals(200L, synced.highestModSeq());
    assertTrue(synced.lastFullSyncEpochS() >= Instant.now().getEpochSecond() - 5);
  }

  @Test
  void qresyncVanishedUidsRemoveLocalMessagesWithoutFullUidScan() throws Exception {
    Account account = new Account(1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p");
    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    TrackingEmailRepository emailRepository = new TrackingEmailRepository();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 42L);
    long lastFullSync = Instant.now().getEpochSecond() - 60;
    inbox = folderRepository.updateSyncCheckpoint(inbox, 100L, lastFullSync);
    emailRepository.seedExisting(1L, inbox.id(), false, false);
    emailRepository.seedExisting(2L, inbox.id(), false, false);

    FakeImapFolderAccess access = new FakeImapFolderAccess();
    access.serverHighestModSeq = 150L;
    access.vanishedUids = new long[] {1L};
    access.changedMessages =
        List.of(new FakeMessage(2L, new Flags(Flags.Flag.SEEN), "Changed subject"));

    NamedFolder synced =
        ImapFolderSyncEngine.syncFolder(
            account.id(), inbox, access, true, true, folderRepository, emailRepository);

    assertTrue(access.openedWithQresync);
    assertEquals(0, access.getMessagesCalls);
    assertTrue(emailRepository.removedUids.contains(1L));
    assertFalse(emailRepository.removedUids.contains(2L));
    assertEquals(150L, synced.highestModSeq());
  }

  private static final class FakeImapFolderAccess implements ImapFolderAccess {
    long uidValidity = 42L;
    long serverHighestModSeq = 200L;
    List<FakeMessage> changedMessages = List.of();
    List<FakeMessage> allMessages = List.of();
    int changedSinceCalls;
    int getMessagesCalls;
    boolean fullSyncRequested;
    boolean opened;
    boolean openedWithQresync;
    long[] vanishedUids = new long[0];

    @Override
    public void openReadOnly(ImapSyncOpenParameters parameters) {
      opened = true;
      openedWithQresync =
          parameters.qresyncSupported()
              && parameters.highestModSeq() != null
              && parameters.highestModSeq() > 0;
    }

    @Override
    public boolean openedWithQresync() {
      return openedWithQresync;
    }

    @Override
    public long[] getVanishedUids() {
      return vanishedUids;
    }

    @Override
    public boolean isOpen() {
      return opened;
    }

    @Override
    public long getUidValidity() {
      return uidValidity;
    }

    @Override
    public long getHighestModSeq() {
      return serverHighestModSeq;
    }

    @Override
    public Message[] getMessagesByUIDChangedSince(long modseq) {
      changedSinceCalls++;
      return changedMessages.toArray(Message[]::new);
    }

    @Override
    public Message[] getMessages() {
      getMessagesCalls++;
      if (changedSinceCalls == 0) {
        fullSyncRequested = true;
      }
      return allMessages.toArray(Message[]::new);
    }

    @Override
    public void fetch(Message[] messages, FetchProfile profile) {}

    @Override
    public long getUid(Message message) {
      return ((FakeMessage) message).uid;
    }

    @Override
    public String getFullName() {
      return "INBOX";
    }

    @Override
    public IMAPFolder unwrapImapFolder() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      opened = false;
    }
  }

  private static final class FakeMessage extends MimeMessage {

    private final long uid;
    private final Flags flags;

    FakeMessage(long uid, Flags flags, String subject) throws MessagingException {
      super(Session.getInstance(new Properties()));
      this.uid = uid;
      this.flags = flags;
      setSubject(subject);
      saveChanges();
    }

    @Override
    public Flags getFlags() {
      return flags;
    }
  }

  private static final class TrackingEmailRepository extends InMemoryEmailRepository {
    boolean updatedFlags;
    boolean clearedFolder;
    final java.util.Set<Long> removedUids = new java.util.HashSet<>();

    void seedExisting(long uid, int folderId, boolean starred, boolean read) {
      addMessages(
          folderId,
          List.of(
              new Email(
                  new net.aggregat4.quicksand.domain.EmailHeader(
                      (int) uid, uid, List.of(), "Subject", null, 0, null, 0, "excerpt", starred,
                      false, read),
                  true,
                  "body",
                  List.of())));
    }

    @Override
    public void updateFlags(int messageId, boolean starred, boolean read) {
      updatedFlags = true;
      super.updateFlags(messageId, starred, read);
    }

    @Override
    public void removeAllByUid(java.util.Collection<Long> localMessageIds) {
      if (!localMessageIds.isEmpty()) {
        removedUids.addAll(localMessageIds);
      }
      if (!localMessageIds.isEmpty()) {
        clearedFolder = true;
      }
      super.removeAllByUid(localMessageIds);
    }
  }
}
