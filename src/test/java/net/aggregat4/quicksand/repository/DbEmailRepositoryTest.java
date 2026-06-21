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
import java.util.Optional;
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
import net.aggregat4.quicksand.domain.SearchOrder;
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
    assertTrue(emailRepository.findByFolderAndUid(inboxId, message.header().imapUid()).isEmpty());
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
            account.id(),
            "fixture subject",
            5,
            SearchOrder.OLDEST,
            PageDirection.RIGHT,
            Optional.empty(),
            0L,
            0,
            false);
    assertEquals(5, subjectResults.emails().size());
    assertFalse(subjectResults.hasLeft());
    assertTrue(subjectResults.hasRight());

    Email rightOffsetMessage = subjectResults.emails().getLast();
    EmailPage nextSubjectResults =
        emailRepository.searchMessages(
            account.id(),
            "fixture subject",
            5,
            SearchOrder.OLDEST,
            PageDirection.RIGHT,
            Optional.empty(),
            rightOffsetMessage.header().receivedDateTimeEpochSeconds(),
            rightOffsetMessage.header().id(),
            false);
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

  private static Email makeMessage(
      long uid, String subject, String body, long receivedEpochSecond) {
    ZonedDateTime when =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(receivedEpochSecond), ZoneId.of("UTC"));
    return new Email(
        new EmailHeader(
            -1,
            uid,
            List.of(new Actor(ActorType.SENDER, "sender@example.com", java.util.Optional.empty())),
            subject,
            when,
            when.toEpochSecond(),
            when,
            when.toEpochSecond(),
            "",
            false,
            false,
            false),
        true,
        body,
        Collections.emptyList());
  }

  @Test
  public void prefixSearchMatchesTokenPrefixWhileQuotedStaysExact()
      throws SQLException, IOException {
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

    emailRepository.addMessage(
        inbox.id(), makeMessage(1, "Launch Digest weekly", "body one", 1000L));
    emailRepository.addMessage(
        inbox.id(), makeMessage(2, "Unrelated", "launch digest body", 2000L));

    // Final unquoted term "dig" is a prefix and matches the token "digest".
    EmailPage prefixResults =
        emailRepository.searchMessages(
            account.id(),
            "launch dig",
            10,
            SearchOrder.NEWEST,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(2, prefixResults.emails().size());
    assertEquals(2, emailRepository.getSearchMessageCount(account.id(), "launch dig"));

    // Quoted "dig" is exact and does not match the token "digest".
    EmailPage quotedResults =
        emailRepository.searchMessages(
            account.id(),
            "launch \"dig\"",
            10,
            SearchOrder.NEWEST,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(0, quotedResults.emails().size());
    assertEquals(0, emailRepository.getSearchMessageCount(account.id(), "launch \"dig\""));

    // A short final term stays exact: "di" does not match "digest".
    assertEquals(0, emailRepository.getSearchMessageCount(account.id(), "launch di"));
  }

  @Test
  public void searchResultsAreScopedToTheAccount() throws SQLException, IOException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(
        new Account(-1, "One", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    accountRepository.createAccountIfNew(
        new Account(-1, "Two", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account accountOne = accountRepository.getAccounts().get(0);
    Account accountTwo = accountRepository.getAccounts().get(1);
    NamedFolder inboxOne =
        folderRepository.createFolder(accountOne, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);
    NamedFolder inboxTwo =
        folderRepository.createFolder(accountTwo, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);

    emailRepository.addMessage(inboxOne.id(), makeMessage(1, "Shared subject", "body", 1000L));
    emailRepository.addMessage(inboxTwo.id(), makeMessage(1, "Shared subject", "body", 1000L));

    EmailPage oneResults =
        emailRepository.searchMessages(
            accountOne.id(),
            "shared subject",
            10,
            SearchOrder.NEWEST,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(1, oneResults.emails().size());
    EmailPage twoResults =
        emailRepository.searchMessages(
            accountTwo.id(),
            "shared subject",
            10,
            SearchOrder.NEWEST,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(1, twoResults.emails().size());
    assertNotEquals(
        oneResults.emails().getFirst().header().id(), twoResults.emails().getFirst().header().id());
  }

  @Test
  public void bestMatchRanksSubjectAboveBodyAndPaginatesByRankCursor()
      throws SQLException, IOException {
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

    // Subject match should outrank a body-only match.
    int bodyOnlyId =
        emailRepository.addMessage(inbox.id(), makeMessage(1, "Unrelated", "launch digest", 1000L));
    int subjectId =
        emailRepository.addMessage(
            inbox.id(), makeMessage(2, "Launch Digest weekly", "plain body", 2000L));

    EmailPage bestMatch =
        emailRepository.searchMessages(
            account.id(),
            "launch dig",
            10,
            SearchOrder.BEST_MATCH,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(2, bestMatch.emails().size());
    assertEquals(subjectId, bestMatch.emails().getFirst().header().id());
    assertEquals(bodyOnlyId, bestMatch.emails().getLast().header().id());
    assertTrue(bestMatch.firstRank().isPresent());
    assertTrue(bestMatch.lastRank().isPresent());
    // Better matches have lower bm25 values.
    assertTrue(bestMatch.firstRank().get() < bestMatch.lastRank().get());
  }

  @Test
  public void relevancePaginationIsDeterministicForEqualScores() throws SQLException, IOException {
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

    // Three messages with identical content (equal bm25) but distinct received dates.
    int idOld =
        emailRepository.addMessage(inbox.id(), makeMessage(1, "Launch Digest", "body", 1000L));
    int idMid =
        emailRepository.addMessage(inbox.id(), makeMessage(2, "Launch Digest", "body", 2000L));
    int idNew =
        emailRepository.addMessage(inbox.id(), makeMessage(3, "Launch Digest", "body", 3000L));

    EmailPage firstPage =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            2,
            SearchOrder.BEST_MATCH,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(2, firstPage.emails().size());
    // Equal ranks: tiebreak by received_date DESC, id DESC -> newest first.
    assertEquals(idNew, firstPage.emails().get(0).header().id());
    assertEquals(idMid, firstPage.emails().get(1).header().id());
    assertFalse(firstPage.hasLeft());
    assertTrue(firstPage.hasRight());
    assertEquals(firstPage.firstRank().get(), firstPage.lastRank().get());

    Email last = firstPage.emails().getLast();
    EmailPage secondPage =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            2,
            SearchOrder.BEST_MATCH,
            PageDirection.RIGHT,
            firstPage.lastRank(),
            last.header().receivedDateTimeEpochSeconds(),
            last.header().id(),
            false);
    assertEquals(1, secondPage.emails().size());
    assertEquals(idOld, secondPage.emails().getFirst().header().id());
    assertTrue(secondPage.hasLeft());
    assertFalse(secondPage.hasRight());

    // Going back LEFT from the second page cursor returns the first page in the same order.
    Email firstOfSecond = secondPage.emails().getFirst();
    EmailPage backPage =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            2,
            SearchOrder.BEST_MATCH,
            PageDirection.LEFT,
            secondPage.firstRank(),
            firstOfSecond.header().receivedDateTimeEpochSeconds(),
            firstOfSecond.header().id(),
            false);
    assertEquals(
        List.of(idNew, idMid), backPage.emails().stream().map(e -> e.header().id()).toList());
    assertFalse(backPage.hasLeft());
    assertTrue(backPage.hasRight());
  }

  @Test
  public void bestMatchEndJumpFetchesLeastRelevantTerminalPage() throws SQLException, IOException {
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

    // Five equal-ranked messages; the caller computes the terminal page size (5 % 2 == 1).
    for (int i = 1; i <= 5; i++) {
      emailRepository.addMessage(inbox.id(), makeMessage(i, "Launch Digest", "body", 1000L + i));
    }
    EmailPage endPage =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            1,
            SearchOrder.BEST_MATCH,
            PageDirection.LEFT,
            Optional.empty(),
            0L,
            0,
            true);
    // Terminal page is the single least-relevant (here: oldest) message.
    assertEquals(1, endPage.emails().size());
    assertEquals(1, endPage.emails().getFirst().header().imapUid());
    assertTrue(endPage.hasLeft());
    assertFalse(endPage.hasRight());
  }

  @Test
  public void searchOrdersNewestAndOldestByDate() throws SQLException, IOException {
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

    int idOld =
        emailRepository.addMessage(inbox.id(), makeMessage(1, "Launch Digest", "body", 1000L));
    int idMid =
        emailRepository.addMessage(inbox.id(), makeMessage(2, "Launch Digest", "body", 2000L));
    int idNew =
        emailRepository.addMessage(inbox.id(), makeMessage(3, "Launch Digest", "body", 3000L));

    EmailPage newest =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            10,
            SearchOrder.NEWEST,
            PageDirection.RIGHT,
            Optional.empty(),
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            false);
    assertEquals(
        List.of(idNew, idMid, idOld), newest.emails().stream().map(e -> e.header().id()).toList());

    EmailPage oldest =
        emailRepository.searchMessages(
            account.id(),
            "launch digest",
            10,
            SearchOrder.OLDEST,
            PageDirection.RIGHT,
            Optional.empty(),
            0L,
            0,
            false);
    assertEquals(
        List.of(idOld, idMid, idNew), oldest.emails().stream().map(e -> e.header().id()).toList());
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

    int inboxId = folderRepository.getFolders(account.id()).getFirst().id();
    Email stored =
        emailRepository
            .findByFolderAndUid(
                inboxId,
                emailRepository
                    .getMessages(inboxId, 1, 0, 0, PageDirection.RIGHT, SortOrder.ASCENDING)
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

  @Test
  public void addMessagePersistsBodyContentHash() throws SQLException, IOException {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    accountRepository.createAccountIfNew(
        new Account(-1, "Hash", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder inbox =
        folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 100L);
    String body = "<html><body><p>Cached HTML body</p></body></html>";
    ZonedDateTime now =
        ZonedDateTime.ofInstant(Instant.parse("2026-03-25T09:15:00Z"), ZoneId.of("UTC"));
    int messageId =
        emailRepository.addMessage(
            inbox.id(),
            new Email(
                new EmailHeader(
                    -1,
                    1L,
                    List.of(
                        new Actor(
                            ActorType.SENDER, "sender@example.com", java.util.Optional.empty())),
                    "Hash subject",
                    now,
                    now.toEpochSecond(),
                    now,
                    now.toEpochSecond(),
                    "Excerpt",
                    false,
                    false,
                    false),
                false,
                body,
                Collections.emptyList()));

    Email loaded = emailRepository.findById(messageId).orElseThrow();
    assertEquals(
        net.aggregat4.quicksand.util.ContentHasher.messageBodyContentHash(body),
        loaded.bodyContentHash());
  }
}
