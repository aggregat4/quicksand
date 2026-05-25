package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.*;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.jobs.ImapStoreSync;
import net.aggregat4.quicksand.service.AttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class DbEmailRepositoryTest {

  @RegisterExtension
  static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

  @Test
  public void emailQueryForDeliveredEmail() throws MessagingException, SQLException, IOException {
    long startOfTestTimestamp = System.currentTimeMillis() / 1000;
    long startOfTestPlusOneHour = startOfTestTimestamp + 3600;
    String subject = GreenMailUtil.random();
    String body = GreenMailUtil.random();
    String from = "from@foo.bar";
    String to = "to@foo.bar";
    GreenmailUtils.deliverMessages(greenMail, subject, body, from, to, 13);
    Store store = GreenmailUtils.getImapStore(greenMail);

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(GreenmailUtils.getAccount());
    Account account = accountRepository.getAccounts().getFirst();
    assertEquals(0, folderRepository.getFolders(account.id()).size());
    ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);
    assertEquals(1, folderRepository.getFolders(account.id()).size());
    assertEquals("INBOX", folderRepository.getFolders(account.id()).get(0).name());

    int pageSize = 5;
    // We retrieve the first page of messages and verify that it is as expected
    EmailPage messages =
        emailRepository.getMessages(
            folderRepository.getFolders(account.id()).get(0).id(),
            pageSize,
            0,
            0,
            PageDirection.RIGHT,
            SortOrder.ASCENDING);
    assertEquals(pageSize, messages.emails().size());
    Email message = messages.emails().get(0);
    assertEquals(from, message.header().getSender().emailAddress());
    assertEquals(to, message.header().getRecipients().get(0).emailAddress());
    Email storedMessage = emailRepository.findById(message.header().id()).orElseThrow();
    assertTrue(storedMessage.plainText());
    assertTrue(storedMessage.body().contains(body));
    assertTrue(storedMessage.header().bodyExcerpt().contains(body));
    assertTrue(emailRepository.findById(Integer.MAX_VALUE).isEmpty());
    long receivedDateTimeEpochSeconds = message.header().receivedDateTimeEpochSeconds();
    assertTrue(
        receivedDateTimeEpochSeconds >= startOfTestTimestamp
            && receivedDateTimeEpochSeconds <= startOfTestPlusOneHour,
        "The received timestamp shoud be less than one hour after the start of the test");
    long sentDateTimeEpochSeconds = message.header().sentDateTimeEpochSeconds();
    assertTrue(
        sentDateTimeEpochSeconds >= startOfTestTimestamp
            && sentDateTimeEpochSeconds <= startOfTestPlusOneHour,
        "The sent timestamp shoud be less than one hour after the start of the test");
    assertFalse(messages.hasLeft());
    assertTrue(messages.hasRight());
    // Retrieve the second page of emails since the page size is 5 and we have 13 total messages
    // there is still a full page left
    Email rightOffsetMessage = messages.emails().get(messages.emails().size() - 1);
    messages =
        emailRepository.getMessages(
            folderRepository.getFolders(account.id()).get(0).id(),
            pageSize,
            rightOffsetMessage.header().receivedDateTimeEpochSeconds(),
            rightOffsetMessage.header().id(),
            PageDirection.RIGHT,
            SortOrder.ASCENDING);
    assertEquals(pageSize, messages.emails().size());
    assertTrue(messages.hasLeft());
    assertTrue(messages.hasRight());
    // And now navigate to the last page, there should only be the remainder of messages (3) left
    rightOffsetMessage = messages.emails().get(messages.emails().size() - 1);
    messages =
        emailRepository.getMessages(
            folderRepository.getFolders(account.id()).get(0).id(),
            pageSize,
            rightOffsetMessage.header().receivedDateTimeEpochSeconds(),
            rightOffsetMessage.header().id(),
            PageDirection.RIGHT,
            SortOrder.ASCENDING);
    assertEquals(3, messages.emails().size());
    assertTrue(messages.hasLeft());
    assertFalse(messages.hasRight());

    int inboxId = folderRepository.getFolders(account.id()).get(0).id();
    emailRepository.removeAllByUid(inboxId, Set.of(message.header().imapUid()));
    assertTrue(emailRepository.findByMessageUid(message.header().imapUid()).isEmpty());
  }

  @Test
  public void searchQueriesMatchSubjectBodyAndSenderWithPaging()
      throws MessagingException, SQLException, IOException {
    String subject = "Search fixture subject";
    String body = "Search fixture body";
    String from = "needle@foo.bar";
    String to = "to@foo.bar";
    GreenmailUtils.deliverMessages(greenMail, subject, body, from, to, 13);
    Store store = GreenmailUtils.getImapStore(greenMail);

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(GreenmailUtils.getAccount());
    Account account = accountRepository.getAccounts().getFirst();
    ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);

    EmailPage subjectResults =
        emailRepository.searchMessages(
            account.id(), "fixture subject", 5, 0, 0, PageDirection.RIGHT, SortOrder.ASCENDING);
    assertEquals(5, subjectResults.emails().size());
    assertFalse(subjectResults.hasLeft());
    assertTrue(subjectResults.hasRight());

    Email rightOffsetMessage = subjectResults.emails().getLast();
    EmailPage nextSubjectResults =
        emailRepository.searchMessages(
            account.id(),
            "fixture subject",
            5,
            rightOffsetMessage.header().receivedDateTimeEpochSeconds(),
            rightOffsetMessage.header().id(),
            PageDirection.RIGHT,
            SortOrder.ASCENDING);
    assertEquals(5, nextSubjectResults.emails().size());
    assertTrue(nextSubjectResults.hasLeft());
    assertTrue(nextSubjectResults.hasRight());

    assertEquals(13, emailRepository.getSearchMessageCount(account.id(), "fixture subject"));
    assertEquals(13, emailRepository.getSearchMessageCount(account.id(), "needle@foo.bar"));
    assertEquals(13, emailRepository.getSearchMessageCount(account.id(), "fixture body"));
    assertEquals(0, emailRepository.getSearchMessageCount(account.id(), "definitely absent"));

    int inboxId = folderRepository.getFolders(account.id()).get(0).id();
    emailRepository.removeAllByUid(
        inboxId, Set.of(subjectResults.emails().getFirst().header().imapUid()));
    assertEquals(12, emailRepository.getSearchMessageCount(account.id(), "fixture subject"));
  }

  @Test
  public void removeAllByUidDeletesMoreThanOneBatch() throws SQLException, IOException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(
        new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);

    int messageCount = 101;
    ZonedDateTime now =
        ZonedDateTime.ofInstant(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    List<Long> uids = new ArrayList<>();
    for (long uid = 1; uid <= messageCount; uid++) {
      uids.add(uid);
      emailRepository.addMessage(
          inbox.id(),
          new Email(
              new EmailHeader(
                  -1,
                  uid,
                  List.of(
                      new Actor(
                          ActorType.SENDER, "sender@example.com", java.util.Optional.empty())),
                  "Subject " + uid,
                  now,
                  now.toEpochSecond(),
                  now,
                  now.toEpochSecond(),
                  "Excerpt",
                  false,
                  false,
                  false),
              true,
              "Body",
              Collections.emptyList()));
    }
    assertEquals(messageCount, emailRepository.getAllMessageIds(inbox.id()).size());
    assertEquals(
        messageCount,
        messageCountForFolder(ds, inbox.id()),
        "messages should exist in SQL before delete");

    emailRepository.removeAllByUid(inbox.id(), uids);

    assertEquals(0, messageCountForFolder(ds, inbox.id()), "messages should be gone after delete");
    assertTrue(emailRepository.getAllMessageIds(inbox.id()).isEmpty());
  }

  private static int messageCountForFolder(DataSource ds, int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT COUNT(*) FROM messages WHERE folder_id = ?",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                rs.next();
                return rs.getInt(1);
              });
        });
  }

  @Test
  public void removeAllByUidIsScopedToFolder() throws SQLException, IOException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(
        new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);
    NamedFolder sent =
        folderRepository.createFolder(account, "Sent", "Sent", FolderSpecialUse.SENT, 101L);

    ZonedDateTime now =
        ZonedDateTime.ofInstant(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    long sharedUid = 7L;
    for (NamedFolder folder : List.of(inbox, sent)) {
      emailRepository.addMessage(
          folder.id(),
          new Email(
              new EmailHeader(
                  -1,
                  sharedUid,
                  List.of(
                      new Actor(
                          ActorType.SENDER, "sender@example.com", java.util.Optional.empty())),
                  "Subject",
                  now,
                  now.toEpochSecond(),
                  now,
                  now.toEpochSecond(),
                  "Excerpt",
                  false,
                  false,
                  false),
              true,
              "Body",
              Collections.emptyList()));
    }

    emailRepository.removeAllByUid(inbox.id(), Set.of(sharedUid));

    assertTrue(emailRepository.getAllMessageIds(inbox.id()).isEmpty());
    assertEquals(Set.of(sharedUid), emailRepository.getAllMessageIds(sent.id()));
  }

  @Test
  public void syncPersistsInboundAttachmentsForDownload() throws Exception {
    String attachmentBody = "db attachment bytes for download";
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress("from@foo.bar"));
    message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress("to@foo.bar"));
    message.setSubject("Attachment sync subject", StandardCharsets.UTF_8.name());

    MimeMultipart mixed = new MimeMultipart("mixed");
    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText("message body with attachment", StandardCharsets.UTF_8.name());
    mixed.addBodyPart(textPart);

    MimeBodyPart attachmentPart = new MimeBodyPart();
    attachmentPart.setText(attachmentBody, StandardCharsets.UTF_8.name());
    attachmentPart.setFileName("sync-note.txt");
    attachmentPart.setDisposition(Part.ATTACHMENT);
    mixed.addBodyPart(attachmentPart);

    message.setContent(mixed);
    message.saveChanges();
    greenMail.setUser("testuser@localhost", "testuser", "testpassword").deliver(message);

    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbAttachmentRepository attachmentRepository = new DbAttachmentRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, attachmentRepository);
    AttachmentService attachmentService = new AttachmentService(attachmentRepository);

    accountRepository.createAccountIfNew(GreenmailUtils.getAccount());
    Account account = accountRepository.getAccounts().getFirst();
    Store store = GreenmailUtils.getImapStore(greenMail);
    ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);

    Email stored =
        emailRepository
            .findByMessageUid(
                emailRepository
                    .getMessages(
                        folderRepository.getFolders(account.id()).getFirst().id(),
                        1,
                        0,
                        0,
                        PageDirection.RIGHT,
                        SortOrder.ASCENDING)
                    .emails()
                    .getFirst()
                    .header()
                    .imapUid())
            .orElseThrow();

    assertTrue(stored.header().attachment());
    assertEquals(1, stored.attachments().size());
    assertEquals("sync-note.txt", stored.attachments().getFirst().name());

    var downloaded =
        attachmentService.getStoredAttachment(stored.attachments().getFirst().id()).orElseThrow();
    assertEquals("sync-note.txt", downloaded.name());
    assertEquals(attachmentBody, new String(downloaded.content().bytes(), StandardCharsets.UTF_8));
  }
}
