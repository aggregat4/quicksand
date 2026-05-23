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
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
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

    emailRepository.removeAllByUid(Set.of(message.header().imapUid()));
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

    emailRepository.removeAllByUid(Set.of(subjectResults.emails().getFirst().header().imapUid()));
    assertEquals(12, emailRepository.getSearchMessageCount(account.id(), "fixture subject"));
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
