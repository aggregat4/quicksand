package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionExecutionState;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionStatus;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbEmailRepositoryMailboxActionTest {

  private DataSource dataSource;
  private DbEmailRepository emailRepository;
  private DbFolderRepository folderRepository;
  private DbAccountFolderMappingRepository mappingRepository;
  private Account account;
  private NamedFolder inbox;
  private NamedFolder trash;
  private int messageId;
  private long messageUid;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = DbTestUtils.getTempSqlite();
    migrateDb(dataSource);
    DbAccountRepository accountRepository = new DbAccountRepository(dataSource);
    accountRepository.createAccountIfNew(
        new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    account = accountRepository.getAccount(accountRepository.getAccounts().getFirst().id());
    folderRepository = new DbFolderRepository(dataSource);
    mappingRepository = new DbAccountFolderMappingRepository(dataSource);
    emailRepository = new DbEmailRepository(dataSource, new DbAttachmentRepository(dataSource));

    inbox = folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);
    trash = folderRepository.createFolder(account, "Trash", "Trash", FolderSpecialUse.TRASH, 200L);
    mappingRepository.save(
        account.id(),
        FolderSpecialUse.TRASH,
        trash.id(),
        trash.remoteName(),
        FolderMappingStatus.USER_CONFIRMED);

    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 9, 15, 0, 0, ZoneId.of("UTC"));
    messageUid = 42L;
    messageId =
        emailRepository.addMessage(
            inbox.id(),
            new Email(
                new EmailHeader(
                    -1,
                    messageUid,
                    java.util.List.of(
                        new Actor(ActorType.SENDER, "a@b.com", java.util.Optional.empty())),
                    "Subject",
                    now,
                    now.toEpochSecond(),
                    now,
                    now.toEpochSecond(),
                    "excerpt",
                    false,
                    false,
                    false),
                true,
                "body",
                java.util.List.of()));
  }

  @Test
  void deleteByIdEnqueuesMoveLikeActionWithSourceIdentity() {
    emailRepository.deleteById(messageId);

    Set<Long> pending =
        emailRepository.getPendingMoveLikeActionSourceUids(
            account.id(), inbox.remoteName(), inbox.uidValidity());
    assertEquals(Set.of(messageUid), pending);
  }

  @Test
  void claimDueMailboxActionsReturnsMoveLikeQueueRows() throws Exception {
    emailRepository.deleteById(messageId);

    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC"));
    java.util.List<MailboxActionQueueRow> claimed = emailRepository.claimDueMailboxActions(now, 10);

    assertEquals(1, claimed.size());
    MailboxActionQueueRow row = claimed.getFirst();
    assertEquals(MailboxActionType.DELETE, row.actionType());
    assertEquals(messageId, row.messageId());
    assertEquals("INBOX", row.sourceRemoteName());
    assertEquals(messageUid, row.sourceUid());
    assertEquals("Trash", row.targetRemoteName());
    assertEquals(MailboxActionStatus.APPLYING.name(), queueStatus(row.id()));
  }

  @Test
  void markMailboxActionPermanentFailureSetsStatusAndError() {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();

    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionPermanentFailure(row.id(), "UID MOVE unsupported", now);

    var status = emailRepository.getMailboxSyncStatus(account.id());
    assertEquals(1, status.failedCount());
    assertTrue(status.needsAttention());
    assertEquals(MailboxActionStatus.FAILED_PERMANENT, status.actions().getFirst().status());
    assertEquals(
        MailboxActionExecutionState.ATTEMPTED_UNKNOWN,
        status.actions().getFirst().executionState());
    assertEquals("UID MOVE unsupported", status.actions().getFirst().lastError());
  }

  @Test
  void markMailboxActionConflictSetsConflictStatus() {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();

    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionConflict(row.id(), "Source UID no longer exists", now);

    var status = emailRepository.getMailboxSyncStatus(account.id());
    assertEquals(1, status.conflictCount());
    assertTrue(status.needsAttention());
    assertEquals(MailboxActionStatus.CONFLICT, status.actions().getFirst().status());
  }

  @Test
  void claimDueReadStateActionsGroupsByFolderAndActionType() throws Exception {
    emailRepository.updateRead(messageId, true);
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC"));

    java.util.List<MailboxActionQueueRow> readBatch =
        emailRepository.claimDueReadStateActions(now, 10);

    assertEquals(1, readBatch.size());
    assertEquals(MailboxActionType.MARK_READ, readBatch.getFirst().actionType());
    assertEquals(MailboxActionStatus.APPLYING.name(), queueStatus(readBatch.getFirst().id()));
    assertTrue(emailRepository.claimDueMailboxActions(now, 10).isEmpty());
  }

  @Test
  void enqueueAppendSentCreatesQueueRowWhenSentMappingExists() throws Exception {
    NamedFolder sent =
        folderRepository.createFolder(account, "Sent", "Sent", FolderSpecialUse.SENT, 300L);
    mappingRepository.save(
        account.id(),
        FolderSpecialUse.SENT,
        sent.id(),
        sent.remoteName(),
        FolderMappingStatus.USER_CONFIRMED);

    DbOutboundMessageRepository outboundRepository = new DbOutboundMessageRepository(dataSource);
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 9, 15, 0, 0, ZoneId.of("UTC"));
    OutboundMessage outbound;
    try (Connection con = dataSource.getConnection()) {
      outbound =
          outboundRepository.create(
              con,
              new OutboundMessage(
                  0,
                  account.id(),
                  java.util.Optional.empty(),
                  account.name(),
                  "sender@example.com",
                  "to@example.com",
                  "",
                  "",
                  "Queued subject",
                  "Queued body",
                  OutboundMessageStatus.SENT,
                  0,
                  java.util.Optional.empty(),
                  now,
                  java.util.Optional.empty(),
                  java.util.Optional.of(now),
                  now.toEpochSecond()));
    }

    emailRepository.enqueueAppendSent(outbound.id());
    emailRepository.enqueueAppendSent(outbound.id());

    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT COUNT(*) FROM mailbox_action_queue WHERE action_type = ? AND payload_json = ?")) {
      stmt.setString(1, MailboxActionType.APPEND_SENT.name());
      stmt.setString(2, Integer.toString(outbound.id()));
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
    }
  }

  @Test
  void scheduleDraftUpsertCoalescesPendingRows() {
    NamedFolder drafts =
        folderRepository.createFolder(account, "Drafts", "Drafts", FolderSpecialUse.DRAFTS, 400L);
    mappingRepository.save(
        account.id(),
        FolderSpecialUse.DRAFTS,
        drafts.id(),
        drafts.remoteName(),
        FolderMappingStatus.USER_CONFIRMED);

    Draft draft =
        new DbDraftRepository(dataSource)
            .create(
                new Draft(
                    0,
                    account.id(),
                    DraftType.NEW,
                    java.util.Optional.empty(),
                    "to@example.com",
                    "",
                    "",
                    "Draft",
                    "Body",
                    false,
                    ZonedDateTime.of(2026, 3, 25, 9, 15, 0, 0, ZoneId.of("UTC")),
                    ZonedDateTime.of(2026, 3, 25, 9, 15, 0, 0, ZoneId.of("UTC")).toEpochSecond()));

    ZonedDateTime first = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC"));
    ZonedDateTime second = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.scheduleDraftUpsert(draft.id(), first);
    emailRepository.scheduleDraftUpsert(draft.id(), second);

    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                """
                    SELECT next_attempt_at_epoch_s
                    FROM mailbox_action_queue
                    WHERE action_type = ? AND payload_json = ?""")) {
      stmt.setString(1, MailboxActionType.UPSERT_DRAFT.name());
      stmt.setString(2, Integer.toString(draft.id()));
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(second.toEpochSecond(), rs.getLong(1));
        assertFalse(rs.next());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void updateMessageImapUidChangesStoredUid() {
    emailRepository.updateMessageImapUid(messageId, 99L);

    Email updated = emailRepository.findById(messageId).orElseThrow();
    assertEquals(99L, updated.header().imapUid());
    assertFalse(emailRepository.findByMessageUid(messageUid).isPresent());
    assertTrue(emailRepository.findByMessageUid(99L).isPresent());
  }

  @Test
  void markMailboxActionSucceededClearsRetryState() {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();

    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionSucceeded(row.id(), now);

    var status = emailRepository.getMailboxSyncStatus(account.id());
    assertTrue(status.actions().isEmpty());
  }

  @Test
  void requestMailboxActionRetryReschedulesFailedAction() throws Exception {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionRetry(row.id(), "Temporary failure", now.plusMinutes(5), now);

    assertTrue(emailRepository.requestMailboxActionRetry(row.id(), account.id(), now));
    assertEquals(MailboxActionStatus.PENDING.name(), queueStatus(row.id()));
  }

  @Test
  void dismissMailboxActionHidesPermanentFailure() {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionPermanentFailure(row.id(), "Unsupported", now);

    assertTrue(emailRepository.dismissMailboxAction(row.id(), account.id(), now));
    assertTrue(emailRepository.getMailboxSyncStatus(account.id()).actions().isEmpty());
  }

  @Test
  void rollbackMailboxActionRestoresLocalFolder() throws Exception {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository.getMailboxSyncStatus(account.id()).actions().getFirst();
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionPermanentFailure(row.id(), "Unsupported", now);
    setExecutionState(row.id(), MailboxActionExecutionState.NOT_ATTEMPTED);

    assertTrue(emailRepository.rollbackMailboxAction(row.id(), account.id(), now));
    assertEquals(inbox.id(), messageFolderId(messageId));
    assertTrue(emailRepository.getMailboxSyncStatus(account.id()).actions().isEmpty());
  }

  @Test
  void purgeStaleMailboxActionRowsRemovesOldSucceededRows() throws Exception {
    emailRepository.deleteById(messageId);
    MailboxActionQueueRow row =
        emailRepository
            .claimDueMailboxActions(ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, ZoneId.of("UTC")), 1)
            .getFirst();
    ZonedDateTime now = ZonedDateTime.of(2026, 3, 25, 10, 5, 0, 0, ZoneId.of("UTC"));
    emailRepository.markMailboxActionSucceeded(row.id(), now);
    setSucceededAt(row.id(), now.minusDays(31));

    assertEquals(1, emailRepository.purgeStaleMailboxActionRows(now));
  }

  private void setExecutionState(int queueId, MailboxActionExecutionState executionState)
      throws Exception {
    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "UPDATE mailbox_action_queue SET execution_state = ? WHERE id = ?")) {
      stmt.setString(1, executionState.name());
      stmt.setInt(2, queueId);
      stmt.executeUpdate();
    }
  }

  private void setSucceededAt(int queueId, ZonedDateTime succeededAt) throws Exception {
    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("UPDATE mailbox_action_queue SET succeeded_at = ? WHERE id = ?")) {
      stmt.setString(
          1, succeededAt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      stmt.setInt(2, queueId);
      stmt.executeUpdate();
    }
  }

  private int messageFolderId(int id) throws Exception {
    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("SELECT folder_id FROM messages WHERE id = ?")) {
      stmt.setInt(1, id);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getInt(1);
      }
    }
  }

  private String queueStatus(int queueId) throws Exception {
    try (Connection con = dataSource.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("SELECT status FROM mailbox_action_queue WHERE id = ?")) {
      stmt.setInt(1, queueId);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }
}
