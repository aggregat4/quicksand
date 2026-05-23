package net.aggregat4.quicksand.service;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.AccountNotificationSummary;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

  private static final Instant FIXED = Instant.parse("2026-03-25T09:15:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneId.of("UTC"));

  private DbFolderRepository folderRepository;
  private DbEmailRepository emailRepository;
  private NotificationService notificationService;
  private NamedFolder inbox;
  private NamedFolder archive;
  private Account account;
  private int accountId;

  @BeforeEach
  void setUp() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    folderRepository = new DbFolderRepository(ds);
    emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));
    notificationService = new NotificationService(folderRepository, emailRepository, CLOCK);

    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Notify",
            "imap.example.test",
            993,
            "user",
            "secret",
            "smtp.example.test",
            587,
            "user",
            "secret"));
    Account loadedAccount = accountRepository.getAccounts().getFirst();
    account = loadedAccount;
    accountId = loadedAccount.id();
    inbox = folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 100L);
    archive =
        folderRepository.createFolder(
            account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 101L);
  }

  @Test
  void countsUnreadMessagesPerFolder() {
    addMessage(inbox.id(), 1L, 1000L, false);
    addMessage(inbox.id(), 2L, 1001L, true);
    addMessage(archive.id(), 3L, 1002L, false);

    AccountNotificationSummary summary = notificationService.getAccountSummary(accountId);

    assertEquals(1, summary.unreadCount(inbox.id()));
    assertEquals(1, summary.unreadCount(archive.id()));
    assertEquals(2, summary.inboxNewSinceView());
  }

  @Test
  void markFolderViewedClearsNewSinceViewForThatFolder() {
    addMessage(inbox.id(), 1L, 1000L, false);
    addMessage(inbox.id(), 2L, 1001L, false);

    notificationService.markFolderViewed(inbox.id());

    AccountNotificationSummary summary = notificationService.getAccountSummary(accountId);
    assertEquals(0, summary.inboxNewSinceView());
    assertEquals(2, summary.unreadCount(inbox.id()));
  }

  @Test
  void sentFolderNeverShowsUnreadBadgeCount() {
    NamedFolder sent =
        folderRepository.createFolder(account, "Sent", "Sent", FolderSpecialUse.SENT, 102L);
    addMessage(inbox.id(), 1L, 1000L, false);
    addMessage(sent.id(), 2L, 1001L, false);

    AccountNotificationSummary summary = notificationService.getAccountSummary(accountId);

    assertEquals(1, summary.unreadCount(inbox.id()));
    assertEquals(0, summary.unreadCount(sent.id()));
  }

  @Test
  void findsMessagesNewerThanListCursor() {
    addMessage(inbox.id(), 1L, 1000L, false);
    addMessage(inbox.id(), 2L, 1005L, false);

    List<EmailHeader> newer = emailRepository.getMessagesNewerThan(inbox.id(), 1000L, 1, 10);

    assertEquals(1, newer.size());
    assertEquals(2L, newer.getFirst().imapUid());
  }

  @Test
  void shouldShowInboxBannerWhenAlreadyViewingInbox() {
    addMessage(inbox.id(), 1L, 1000L, false);

    AccountNotificationSummary summary = notificationService.getAccountSummary(accountId);
    NotificationService.InboxNotification onInbox =
        notificationService.inboxNotification(summary, Optional.of(inbox.id()), inbox);
    NotificationService.InboxNotification onArchive =
        notificationService.inboxNotification(summary, Optional.of(archive.id()), inbox);

    assertTrue(onInbox.show());
    assertFalse(onInbox.linked());
    assertEquals("1 new in this folder", onInbox.message());
    assertTrue(onArchive.show());
    assertTrue(onArchive.linked());
    assertEquals("1 new in Inbox", onArchive.message());
  }

  private void addMessage(int folderId, long uid, long receivedEpochS, boolean read) {
    EmailHeader header =
        new EmailHeader(
            -1,
            uid,
            List.of(new Actor(ActorType.SENDER, "sender@example.com", Optional.of("Sender"))),
            "Subject " + uid,
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(receivedEpochS), ZoneId.of("UTC")),
            receivedEpochS,
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(receivedEpochS), ZoneId.of("UTC")),
            receivedEpochS,
            "Excerpt",
            false,
            false,
            read);
    emailRepository.addMessage(folderId, new Email(header, true, "Body", Collections.emptyList()));
  }
}
