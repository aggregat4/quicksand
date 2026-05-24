package net.aggregat4.quicksand.jobs;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.repository.AccountRepository;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbDraftRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import net.aggregat4.quicksand.repository.DbOutboundMessageRepository;
import net.aggregat4.quicksand.service.OutboundMessageService;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MailboxActionSyncTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));

  @RegisterExtension
  static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

  @Test
  void syncsQueuedMarkReadActionToImap() throws Exception {
    SyncFixture fixture = syncInboxMessage("read-sync-body");
    fixture.emailRepository().updateRead(fixture.message().header().id(), true);

    runMailboxActionSync(fixture);

    assertRemoteSeen();
    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.MARK_READ));
  }

  @Test
  void syncsBatchedMarkReadActionsToImapInOneSession() throws Exception {
    SyncFixture fixture = syncInboxMessages("batch-read-body", 12);
    int inboxFolderId =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    EmailPage page =
        fixture
            .emailRepository()
            .getMessages(
                inboxFolderId, 20, Long.MAX_VALUE, 0, PageDirection.RIGHT, SortOrder.DESCENDING);
    for (Email email : page.emails()) {
      fixture.emailRepository().updateRead(email.header().id(), true);
    }

    runMailboxActionSync(fixture);

    assertAllRemoteSeen(page.emails().size());
    assertEquals(
        12, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "SUCCEEDED"));
    assertEquals(
        0, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING"));
  }

  @Test
  void queuedMarkReadActionsStayPendingUntilMailboxActionSyncRuns() throws Exception {
    SyncFixture fixture = syncInboxMessages("pending-until-sync", 5);
    int inboxFolderId =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    EmailPage page =
        fixture
            .emailRepository()
            .getMessages(
                inboxFolderId, 10, Long.MAX_VALUE, 0, PageDirection.RIGHT, SortOrder.DESCENDING);
    for (Email email : page.emails()) {
      fixture.emailRepository().updateRead(email.header().id(), true);
    }

    assertEquals(
        5, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING"));

    runMailboxActionSync(fixture);

    assertEquals(
        5, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "SUCCEEDED"));
    assertEquals(
        0, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING"));
  }

  @Test
  void drainsAllQueuedMarkReadActionsWithinSyncCycles() throws Exception {
    int messageCount = 100;
    SyncFixture fixture = syncInboxMessages("bulk-read-body", messageCount);
    int inboxFolderId =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    EmailPage page =
        fixture
            .emailRepository()
            .getMessages(
                inboxFolderId,
                messageCount + 5,
                Long.MAX_VALUE,
                0,
                PageDirection.RIGHT,
                SortOrder.DESCENDING);
    assertEquals(messageCount, page.emails().size());
    for (Email email : page.emails()) {
      fixture.emailRepository().updateRead(email.header().id(), true);
    }
    assertEquals(
        messageCount,
        queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING"));

    MailboxActionSync sync =
        new MailboxActionSync(
            fixture.accountRepository(),
            fixture.emailRepository(),
            new DbOutboundMessageRepository(fixture.dataSource()),
            new DbAttachmentRepository(fixture.dataSource()),
            new DbDraftRepository(fixture.dataSource()),
            fixture.folderRepository(),
            FIXED_CLOCK,
            15,
            60);

    for (int cycle = 0; cycle < 10; cycle++) {
      sync.syncNow();
      if (queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING") == 0
          && queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "APPLYING")
              == 0) {
        break;
      }
    }

    assertEquals(
        messageCount,
        queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "SUCCEEDED"));
    assertEquals(
        0, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "PENDING"));
    assertEquals(
        0, queuedActionCount(fixture.dataSource(), MailboxActionType.MARK_READ, "APPLYING"));
    assertAllRemoteSeen(messageCount);
  }

  @Test
  void syncsQueuedArchiveActionToImap() throws Exception {
    SyncFixture fixture =
        configureMappedFolder(
            syncInboxMessage("archive-sync-body"), "Archive", FolderSpecialUse.ARCHIVE);

    fixture.emailRepository().archiveById(fixture.message().header().id());
    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.ARCHIVE));
    assertRemoteFolderCounts("INBOX", 0, "Archive", 1);
  }

  @Test
  void syncsQueuedDeleteActionToTrashOnImap() throws Exception {
    SyncFixture fixture =
        configureMappedFolder(
            syncInboxMessage("delete-sync-body"), "Trash", FolderSpecialUse.TRASH);

    fixture.emailRepository().deleteById(fixture.message().header().id());
    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.DELETE));
    assertRemoteFolderCounts("INBOX", 0, "Trash", 1);
  }

  @Test
  void syncsQueuedMarkSpamActionToImap() throws Exception {
    SyncFixture fixture =
        configureMappedFolder(syncInboxMessage("spam-sync-body"), "Spam", FolderSpecialUse.JUNK);

    fixture.emailRepository().markSpamById(fixture.message().header().id());
    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.MARK_SPAM));
    assertRemoteFolderCounts("INBOX", 0, "Spam", 1);
  }

  @Test
  void syncsQueuedAppendSentActionToImap() throws Exception {
    SyncFixture fixture =
        configureMappedFolder(syncInboxMessage("append-sent-body"), "Sent", FolderSpecialUse.SENT);

    DbDraftRepository draftRepository = new DbDraftRepository(fixture.dataSource());
    Draft draft =
        draftRepository.create(
            new Draft(
                0,
                fixture.account().id(),
                DraftType.NEW,
                java.util.Optional.empty(),
                "recipient@localhost",
                "",
                "",
                "Append sent subject",
                "Append sent body",
                false,
                java.time.ZonedDateTime.now(FIXED_CLOCK),
                FIXED_CLOCK.instant().getEpochSecond()));

    DbOutboundMessageRepository outboundMessageRepository =
        new DbOutboundMessageRepository(fixture.dataSource());
    OutboundMessageService outboundMessageService =
        new OutboundMessageService(
            fixture.dataSource(),
            fixture.accountRepository(),
            draftRepository,
            new DbAttachmentRepository(fixture.dataSource()),
            outboundMessageRepository,
            fixture.emailRepository(),
            FIXED_CLOCK);
    OutboundMessage queued = outboundMessageService.queueDraftForDelivery(draft.id()).orElseThrow();

    MailSender mailSender =
        new MailSender(
            fixture.accountRepository(),
            outboundMessageRepository,
            new DbAttachmentRepository(fixture.dataSource()),
            fixture.emailRepository(),
            FIXED_CLOCK,
            60,
            3,
            60);
    mailSender.sendNow();

    assertEquals(
        MailboxActionStatus.PENDING.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.APPEND_SENT));

    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.APPEND_SENT));
    assertRemoteFolderCounts("INBOX", 1, "Sent", 1);

    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder sent = store.getFolder("Sent");
    sent.open(Folder.READ_ONLY);
    try {
      assertEquals("Append sent subject", sent.getMessage(1).getSubject());
    } finally {
      sent.close();
      store.close();
    }
  }

  @Test
  void syncsQueuedDraftUpsertToImapAndCoalescesAutosaves() throws Exception {
    SyncFixture fixture =
        configureMappedFolder(
            syncInboxMessage("draft-sync-body"), "Drafts", FolderSpecialUse.DRAFTS);

    DbDraftRepository draftRepository = new DbDraftRepository(fixture.dataSource());
    Draft draft =
        draftRepository.create(
            new Draft(
                0,
                fixture.account().id(),
                DraftType.NEW,
                java.util.Optional.empty(),
                "to@localhost",
                "",
                "",
                "Draft sync subject",
                "Draft sync body v1",
                false,
                java.time.ZonedDateTime.now(FIXED_CLOCK),
                FIXED_CLOCK.instant().getEpochSecond()));

    ZonedDateTime firstAttempt = java.time.ZonedDateTime.now(FIXED_CLOCK);
    fixture.emailRepository().scheduleDraftUpsert(draft.id(), firstAttempt.plusSeconds(60));
    fixture.emailRepository().scheduleDraftUpsert(draft.id(), firstAttempt.plusSeconds(120));

    try (Connection con = fixture.dataSource().getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT COUNT(*) FROM mailbox_action_queue WHERE action_type = ? AND payload_json = ?")) {
      stmt.setString(1, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(2, Integer.toString(draft.id()));
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
    }

    draftRepository.update(
        draft.withContent(
            "to@localhost", "", "", "Draft sync subject", "Draft sync body v2", firstAttempt));

    try (Connection con = fixture.dataSource().getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                """
                    UPDATE mailbox_action_queue
                    SET next_attempt_at_epoch_s = ?, status = ?
                    WHERE action_type = ? AND payload_json = ?""")) {
      stmt.setLong(1, firstAttempt.toEpochSecond() - 1);
      stmt.setString(2, MailboxActionStatus.PENDING.name());
      stmt.setString(3, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(4, Integer.toString(draft.id()));
      stmt.executeUpdate();
    }

    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.UPSERT_DRAFT));
    assertRemoteFolderCounts("INBOX", 1, "Drafts", 1);

    Draft updated = draftRepository.findById(draft.id()).orElseThrow();
    assertTrue(updated.remoteImapUid().isPresent());

    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder drafts = store.getFolder("Drafts");
    drafts.open(Folder.READ_ONLY);
    try {
      assertEquals("Draft sync subject", drafts.getMessage(1).getSubject());
    } finally {
      drafts.close();
      store.close();
    }
  }

  @Test
  void syncsQueuedMoveActionToImapAndUpdatesLocalUid() throws Exception {
    SyncFixture fixture = syncInboxMessageWithExtraFolders("move-sync-body", "Target");

    NamedFolder target =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(folder -> "Target".equals(folder.remoteName()))
            .findFirst()
            .orElseThrow();
    fixture.emailRepository().moveToFolderById(fixture.message().header().id(), target.id());
    runMailboxActionSync(fixture);

    assertEquals(
        MailboxActionStatus.SUCCEEDED.name(),
        queuedActionStatus(fixture.dataSource(), MailboxActionType.MOVE));
    assertRemoteFolderCounts("INBOX", 0, "Target", 1);

    Email updated =
        fixture.emailRepository().findById(fixture.message().header().id()).orElseThrow();
    assertEquals(remoteMessageUid("Target", 1), updated.header().imapUid());
  }

  private static SyncFixture configureMappedFolder(
      SyncFixture fixture, String remoteFolderName, FolderSpecialUse specialUse) throws Exception {
    ensureRemoteFolderExists(remoteFolderName);
    fixture = resync(fixture);
    NamedFolder folder =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(candidate -> remoteFolderName.equals(candidate.remoteName()))
            .findFirst()
            .orElseThrow();
    new DbAccountFolderMappingRepository(fixture.dataSource())
        .save(
            fixture.account().id(),
            specialUse,
            folder.id(),
            folder.remoteName(),
            FolderMappingStatus.USER_CONFIRMED);
    return fixture;
  }

  private static SyncFixture syncInboxMessage(String body) throws Exception {
    return syncInboxMessages(body, 1);
  }

  private static SyncFixture syncInboxMessages(String body, int messageCount) throws Exception {
    for (int index = 0; index < messageCount; index++) {
      GreenmailUtils.deliverOneMessage(
          greenMail, GreenMailUtil.random(), body + "-" + index, "from@foo.bar", "to@foo.bar");
    }

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Mailbox Action Account",
            "localhost",
            greenMail.getImap().getServerSetup().getPort(),
            "testuser",
            "testpassword",
            "localhost",
            greenMail.getSmtp().getServerSetup().getPort(),
            "testuser",
            "testpassword"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    Store syncStore = GreenmailUtils.getImapStore(greenMail);
    ImapStoreSync.syncImapFolders(account, syncStore, folderRepository, emailRepository);

    int inboxFolderId =
        folderRepository.getFolders(account.id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    Email message = onlySyncedMessage(emailRepository, inboxFolderId, messageCount);
    return new SyncFixture(
        ds, account, accountRepository, folderRepository, emailRepository, message);
  }

  private static SyncFixture syncInboxMessageWithExtraFolders(String body, String... extraFolders)
      throws Exception {
    GreenmailUtils.deliverOneMessage(
        greenMail, GreenMailUtil.random(), body, "from@foo.bar", "to@foo.bar");

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Mailbox Action Account",
            "localhost",
            greenMail.getImap().getServerSetup().getPort(),
            "testuser",
            "testpassword",
            "localhost",
            greenMail.getSmtp().getServerSetup().getPort(),
            "testuser",
            "testpassword"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    Store syncStore = GreenmailUtils.getImapStore(greenMail);
    for (String extraFolder : extraFolders) {
      Folder folder = syncStore.getFolder(extraFolder);
      if (!folder.exists()) {
        folder.create(Folder.HOLDS_MESSAGES);
      }
    }
    ImapStoreSync.syncImapFolders(account, syncStore, folderRepository, emailRepository);

    int inboxFolderId =
        folderRepository.getFolders(account.id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    Email message = onlySyncedMessage(emailRepository, inboxFolderId);
    return new SyncFixture(
        ds, account, accountRepository, folderRepository, emailRepository, message);
  }

  private static SyncFixture resync(SyncFixture fixture) throws Exception {
    Store syncStore = GreenmailUtils.getImapStore(greenMail);
    ImapStoreSync.syncImapFolders(
        fixture.account(), syncStore, fixture.folderRepository(), fixture.emailRepository());
    int inboxFolderId =
        fixture.folderRepository().getFolders(fixture.account().id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow()
            .id();
    Email message = onlySyncedMessage(fixture.emailRepository(), inboxFolderId);
    return new SyncFixture(
        fixture.dataSource(),
        fixture.account(),
        fixture.accountRepository(),
        fixture.folderRepository(),
        fixture.emailRepository(),
        message);
  }

  private static void runMailboxActionSync(SyncFixture fixture) {
    new MailboxActionSync(
            fixture.accountRepository(),
            fixture.emailRepository(),
            new DbOutboundMessageRepository(fixture.dataSource()),
            new DbAttachmentRepository(fixture.dataSource()),
            new DbDraftRepository(fixture.dataSource()),
            fixture.folderRepository(),
            FIXED_CLOCK,
            60,
            60)
        .syncNow();
  }

  private static void ensureRemoteFolderExists(String folderName) throws Exception {
    Store store = GreenmailUtils.getImapStore(greenMail);
    try {
      Folder folder = store.getFolder(folderName);
      if (!folder.exists()) {
        folder.create(Folder.HOLDS_MESSAGES);
      }
    } finally {
      store.close();
    }
  }

  private static Email onlySyncedMessage(DbEmailRepository emailRepository, int folderId) {
    return onlySyncedMessage(emailRepository, folderId, 1);
  }

  private static Email onlySyncedMessage(
      DbEmailRepository emailRepository, int folderId, int expectedCount) {
    EmailPage page =
        emailRepository.getMessages(
            folderId, expectedCount + 5, 0, 0, PageDirection.RIGHT, SortOrder.ASCENDING);
    assertEquals(expectedCount, page.emails().size());
    return page.emails().getFirst();
  }

  private static void assertRemoteSeen() throws Exception {
    assertAllRemoteSeen(1);
  }

  private static void assertAllRemoteSeen(int expectedCount) throws Exception {
    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder inbox = store.getFolder("INBOX");
    inbox.open(Folder.READ_ONLY);
    try {
      assertEquals(expectedCount, inbox.getMessageCount());
      for (Message message : inbox.getMessages()) {
        assertTrue(message.isSet(Flags.Flag.SEEN));
      }
    } finally {
      inbox.close();
      store.close();
    }
  }

  private static long remoteMessageUid(String folderName, int messageNumber) throws Exception {
    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder folder = store.getFolder(folderName);
    folder.open(Folder.READ_ONLY);
    try {
      Message message = folder.getMessage(messageNumber);
      return ((IMAPFolder) folder).getUID(message);
    } finally {
      folder.close();
      store.close();
    }
  }

  private static void assertRemoteFolderCounts(
      String sourceFolder, int sourceCount, String targetFolder, int targetCount) throws Exception {
    Store store = GreenmailUtils.getImapStore(greenMail);
    try {
      Folder source = store.getFolder(sourceFolder);
      source.open(Folder.READ_ONLY);
      assertEquals(sourceCount, source.getMessageCount());
      source.close();

      Folder target = store.getFolder(targetFolder);
      target.open(Folder.READ_ONLY);
      assertEquals(targetCount, target.getMessageCount());
      target.close();
    } finally {
      store.close();
    }
  }

  private static String queuedActionStatus(DataSource ds, MailboxActionType actionType)
      throws Exception {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("SELECT status FROM mailbox_action_queue WHERE action_type = ?")) {
      stmt.setString(1, actionType.name());
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private static int queuedActionCount(DataSource ds, MailboxActionType actionType, String status)
      throws Exception {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT COUNT(*) FROM mailbox_action_queue WHERE action_type = ? AND status = ?")) {
      stmt.setString(1, actionType.name());
      stmt.setString(2, status);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getInt(1);
      }
    }
  }

  private record SyncFixture(
      DataSource dataSource,
      Account account,
      AccountRepository accountRepository,
      DbFolderRepository folderRepository,
      DbEmailRepository emailRepository,
      Email message) {}
}
