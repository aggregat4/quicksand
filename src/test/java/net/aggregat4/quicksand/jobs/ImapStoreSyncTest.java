package net.aggregat4.quicksand.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import net.aggregat4.quicksand.repository.DatabaseMaintenance;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbAttachmentRepository;
import net.aggregat4.quicksand.repository.DbEmailRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class ImapStoreSyncTest {
  private static final String TEST_ADDRESS = "testuser@localhost";
  private static final String TEST_USERNAME = "testuser";
  private static final String TEST_PASSWORD = "testpassword";

  @RegisterExtension
  static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

  @Test
  public void fullSyncStoresCheckpointOnServersWithoutCondstore() throws MessagingException {
    String subject = GreenMailUtil.random();
    String body = GreenMailUtil.random();
    GreenmailUtils.deliverOneMessage(greenMail, subject, body, "from@foo.bar", "to@foo.bar");
    Store store = GreenmailUtils.getImapStore(greenMail);

    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();
    Account account = GreenmailUtils.getAccount();

    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);

    NamedFolder inbox = folderRepository.getFolders(account.id()).getFirst();
    assertNotNull(inbox.lastFullSyncEpochS());
    assertNull(inbox.highestModSeq());
  }

  @Test
  public void naiveFolderSyncAgainstEmptyDatabase() throws MessagingException {
    String subject = GreenMailUtil.random();
    String body = GreenMailUtil.random();
    GreenmailUtils.deliverOneMessage(greenMail, subject, body, "from@foo.bar", "to@foo.bar");
    Store store = GreenmailUtils.getImapStore(greenMail);

    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();

    Account account = GreenmailUtils.getAccount();
    assertEquals(0, folderRepository.getFolders(account.id()).size());
    // Sync the imap store and verify that we now have one message in the inbox locally
    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
    assertEquals(1, folderRepository.getFolders(account.id()).size());
    NamedFolder inbox = folderRepository.getFolders(account.id()).get(0);
    assertEquals("INBOX", inbox.name());
    assertEquals("INBOX", inbox.remoteName());
    assertEquals(FolderSpecialUse.INBOX, inbox.specialUse());
    assertNotNull(inbox.uidValidity());
    assertTrue(inbox.uidValidity() > 0);
    assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
    long storedUid = messageRepository.getAllMessageIds(inbox.id()).iterator().next();
    assertTrue(storedUid > 0);
    Email email = messageRepository.findByFolderAndUid(inbox.id(), storedUid).orElseThrow();
    assertEquals(subject, email.header().subject());
    assertTrue(email.plainText());
    assertTrue(email.body().contains(body));
    assertTrue(email.header().bodyExcerpt().contains(body));
    // attachment handling covered by multipart/mixed tests below
    // TODO: message update handling

    Folder imapFolder = store.getFolder("INBOX");
    if (imapFolder.isOpen()) {
      imapFolder.close();
    }
    // delete all messages in the inbox
    imapFolder.open(Folder.READ_WRITE);
    Message message = imapFolder.getMessage(1);
    assertEquals(storedUid, ((IMAPFolder) imapFolder).getUID(message));

    // mark the message as read
    message.setFlag(Flags.Flag.SEEN, true);
    assertEquals(1, imapFolder.getMessageCount());
    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
    // the INBOX folder can not be deleted
    assertEquals(1, folderRepository.getFolders(account.id()).size());
    // the local message should still be tracked by the same server UID
    assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
    long updatedStoredUid = messageRepository.getAllMessageIds(inbox.id()).iterator().next();
    assertEquals(storedUid, updatedStoredUid);
    email = messageRepository.findByFolderAndUid(inbox.id(), updatedStoredUid).orElseThrow();
    assertTrue(email.header().read());

    // mark the message as deleted
    message.setFlag(Flags.Flag.DELETED, true);
    imapFolder.expunge();
    assertEquals(0, imapFolder.getMessageCount());
    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);
    // the INBOX folder can not be deleted
    assertEquals(1, folderRepository.getFolders(account.id()).size());
    // but the messages should all be gone
    assertEquals(0, messageRepository.getAllMessageIds(inbox.id()).size());
  }

  @Test
  public void mapsImapSpecialUseAttributes() {
    assertEquals(
        FolderSpecialUse.ARCHIVE,
        ImapStoreSync.specialUseFromAttributes(new String[] {"\\HasNoChildren", "\\Archive"})
            .orElseThrow());
    assertEquals(
        FolderSpecialUse.JUNK,
        ImapStoreSync.specialUseFromAttributes(new String[] {"\\Spam"}).orElseThrow());
    assertTrue(ImapStoreSync.specialUseFromAttributes(new String[] {"\\HasChildren"}).isEmpty());
  }

  @Test
  public void resolveSpecialUsePrefersRemoteAttributeButPreservesLocalRole() {
    NamedFolder localArchive =
        new NamedFolder(
            1,
            "Archive",
            0,
            "Archive",
            FolderSpecialUse.ARCHIVE,
            1L,
            true,
            FolderMappingStatus.MISSING,
            null,
            null);
    assertEquals(
        FolderSpecialUse.ARCHIVE,
        ImapStoreSync.resolveSpecialUse(localArchive, FolderSpecialUse.ARCHIVE));
    assertEquals(FolderSpecialUse.ARCHIVE, ImapStoreSync.resolveSpecialUse(localArchive, null));
    assertEquals(
        FolderSpecialUse.ARCHIVE,
        ImapStoreSync.resolveSpecialUse(
            new NamedFolder(
                2,
                "Trash",
                0,
                "Trash",
                FolderSpecialUse.TRASH,
                1L,
                true,
                FolderMappingStatus.MISSING,
                null,
                null),
            FolderSpecialUse.ARCHIVE));
  }

  @Test
  public void syncPreservesLocallyAssignedSpecialUseWhenImapOmitsAttributes()
      throws MessagingException {
    greenMail.setUser(TEST_ADDRESS, TEST_USERNAME, TEST_PASSWORD);
    Store store = GreenmailUtils.getImapStore(greenMail);
    Folder archiveFolder = store.getFolder("Archive");
    if (!archiveFolder.exists()) {
      assertTrue(archiveFolder.create(Folder.HOLDS_MESSAGES));
    }

    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();
    Account account = GreenmailUtils.getAccount();
    folderRepository.createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 1L);

    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);

    NamedFolder syncedArchive =
        folderRepository.getFolders(account.id()).stream()
            .filter(folder -> "Archive".equals(folder.remoteName()))
            .findFirst()
            .orElseThrow();
    assertEquals(FolderSpecialUse.ARCHIVE, syncedArchive.specialUse());
  }

  @Test
  public void syncDoesNotDownloadSourceUidCoveredByPendingMoveLikeAction()
      throws MessagingException {
    String subject = GreenMailUtil.random();
    String body = GreenMailUtil.random();
    GreenmailUtils.deliverOneMessage(greenMail, subject, body, "from@foo.bar", "to@foo.bar");
    Store store = GreenmailUtils.getImapStore(greenMail);
    IMAPFolder imapFolder = (IMAPFolder) store.getFolder("INBOX");
    imapFolder.open(Folder.READ_ONLY);
    long pendingSourceUid = imapFolder.getUID(imapFolder.getMessage(1));
    imapFolder.close();

    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();
    messageRepository.addPendingMoveLikeActionSourceUid(pendingSourceUid);

    Account account = GreenmailUtils.getAccount();
    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);

    NamedFolder inbox = folderRepository.getFolders(account.id()).getFirst();
    assertEquals("INBOX", inbox.remoteName());
    assertEquals(0, messageRepository.getAllMessageIds(inbox.id()).size());
  }

  @Test
  public void syncStoresHtmlOnlyMessageBody() throws MessagingException {
    String htmlBody = "<html><body><h1>HTML only body</h1><p>html-only-token</p></body></html>";
    MimeMessage message = createBaseMessage("HTML only subject");
    message.setContent(htmlBody, "text/html; charset=UTF-8");
    message.saveChanges();
    deliver(message);

    Email email = syncOnlyMessage();

    assertFalse(email.plainText());
    assertTrue(email.body().contains("html-only-token"));
    assertTrue(email.header().bodyExcerpt().contains("html-only-token"));
  }

  @Test
  public void syncPrefersHtmlFromMultipartAlternative() throws MessagingException {
    MimeMessage message = createBaseMessage("Alternative subject");
    MimeMultipart alternative = new MimeMultipart("alternative");

    MimeBodyPart plainPart = new MimeBodyPart();
    plainPart.setText("plain alternative body", StandardCharsets.UTF_8.name());
    alternative.addBodyPart(plainPart);

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(
        "<html><body><p>html alternative body</p></body></html>", "text/html; charset=UTF-8");
    alternative.addBodyPart(htmlPart);

    message.setContent(alternative);
    message.saveChanges();
    deliver(message);

    Email email = syncOnlyMessage();

    assertFalse(email.plainText());
    assertTrue(email.body().contains("html alternative body"));
    assertFalse(email.body().contains("plain alternative body"));
  }

  @Test
  public void syncSelectsNestedHtmlAlternativeInsideMultipartMixed() throws MessagingException {
    MimeMessage message = createBaseMessage("Nested mixed alternative subject");
    MimeMultipart mixed = new MimeMultipart("mixed");

    MimeMultipart alternative = new MimeMultipart("alternative");
    MimeBodyPart plainPart = new MimeBodyPart();
    plainPart.setText("nested plain alternative body", StandardCharsets.UTF_8.name());
    alternative.addBodyPart(plainPart);

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(
        "<html><body><p>nested html alternative body</p></body></html>",
        "text/html; charset=UTF-8");
    alternative.addBodyPart(htmlPart);

    MimeBodyPart alternativePart = new MimeBodyPart();
    alternativePart.setContent(alternative);
    mixed.addBodyPart(alternativePart);

    MimeBodyPart attachmentPart = new MimeBodyPart();
    attachmentPart.setText(
        "nested attachment body must not be indexed", StandardCharsets.UTF_8.name());
    attachmentPart.setFileName("nested-note.txt");
    attachmentPart.setDisposition(Part.ATTACHMENT);
    mixed.addBodyPart(attachmentPart);

    message.setContent(mixed);
    message.saveChanges();
    deliver(message);

    Email email = syncOnlyMessage();

    assertFalse(email.plainText());
    assertTrue(email.body().contains("nested html alternative body"));
    assertFalse(email.body().contains("nested plain alternative body"));
    assertFalse(email.body().contains("nested attachment body must not be indexed"));
    assertTrue(email.header().bodyExcerpt().contains("nested html alternative body"));
    assertFalse(
        email.header().bodyExcerpt().contains("nested attachment body must not be indexed"));
    assertTrue(email.header().attachment());
    assertEquals(1, email.inboundAttachments().size());
    assertEquals("nested-note.txt", email.inboundAttachments().getFirst().name());
    assertEquals(
        "nested attachment body must not be indexed",
        new String(
            email.inboundAttachments().getFirst().content().bytes(), StandardCharsets.UTF_8));
  }

  @Test
  public void syncStoresNamedHtmlAttachmentInMultipartMixed() throws MessagingException {
    MimeMessage message = createBaseMessage("HTML attachment subject");
    MimeMultipart mixed = new MimeMultipart("mixed");

    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText("real message body", StandardCharsets.UTF_8.name());
    mixed.addBodyPart(textPart);

    MimeBodyPart htmlAttachmentPart = new MimeBodyPart();
    htmlAttachmentPart.setContent(
        "<html><body><p>attached html file body</p></body></html>", "text/html; charset=UTF-8");
    htmlAttachmentPart.setFileName("report.html");
    htmlAttachmentPart.setDisposition(Part.INLINE);
    mixed.addBodyPart(htmlAttachmentPart);

    message.setContent(mixed);
    message.saveChanges();
    deliver(message);

    Email email = syncOnlyMessage();

    assertTrue(email.header().attachment());
    assertEquals(1, email.inboundAttachments().size());
    assertEquals("report.html", email.inboundAttachments().getFirst().name());
    assertTrue(
        new String(email.inboundAttachments().getFirst().content().bytes(), StandardCharsets.UTF_8)
            .contains("attached html file body"));
  }

  @Test
  public void syncIgnoresAttachmentBodyInMultipartMixed() throws MessagingException {
    MimeMessage message = createBaseMessage("Mixed subject");
    MimeMultipart mixed = new MimeMultipart("mixed");

    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText("real message body", StandardCharsets.UTF_8.name());
    mixed.addBodyPart(textPart);

    MimeBodyPart attachmentPart = new MimeBodyPart();
    attachmentPart.setText("attachment body must not be indexed", StandardCharsets.UTF_8.name());
    attachmentPart.setFileName("note.txt");
    attachmentPart.setDisposition(Part.ATTACHMENT);
    mixed.addBodyPart(attachmentPart);

    message.setContent(mixed);
    message.saveChanges();
    deliver(message);

    Email email = syncOnlyMessage();

    assertTrue(email.plainText());
    assertTrue(email.body().contains("real message body"));
    assertFalse(email.body().contains("attachment body must not be indexed"));
    assertTrue(email.header().bodyExcerpt().contains("real message body"));
    assertFalse(email.header().bodyExcerpt().contains("attachment body must not be indexed"));
    assertTrue(email.header().attachment());
    assertEquals(1, email.inboundAttachments().size());
    assertEquals("note.txt", email.inboundAttachments().getFirst().name());
    assertEquals(
        "attachment body must not be indexed",
        new String(
            email.inboundAttachments().getFirst().content().bytes(), StandardCharsets.UTF_8));
  }

  @Test
  void doesNotRevertLocalReadStateWhileMarkReadActionsArePending() throws Exception {
    for (int i = 0; i < 10; i++) {
      GreenmailUtils.deliverOneMessage(
          greenMail, GreenMailUtil.random(), "body-" + i, "from@foo.bar", "to@foo.bar");
    }

    DataSource ds = DbTestUtils.getTempSqlite();
    DatabaseMaintenance.migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Read Sync Account",
            "localhost",
            greenMail.getImap().getServerSetup().getPort(),
            TEST_USERNAME,
            TEST_PASSWORD,
            "localhost",
            greenMail.getSmtp().getServerSetup().getPort(),
            TEST_USERNAME,
            TEST_PASSWORD));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbEmailRepository emailRepository = new DbEmailRepository(ds, new DbAttachmentRepository(ds));

    Store store = GreenmailUtils.getImapStore(greenMail);
    ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);

    NamedFolder inbox =
        folderRepository.getFolders(account.id()).stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst()
            .orElseThrow();
    EmailPage syncedMessages =
        emailRepository.getMessages(
            inbox.id(), 20, Long.MAX_VALUE, 0, PageDirection.RIGHT, SortOrder.DESCENDING);
    assertEquals(10, syncedMessages.emails().size());
    for (Email email : syncedMessages.emails()) {
      assertFalse(email.header().read());
      emailRepository.updateRead(email.header().id(), true);
    }

    assertEquals(
        10,
        emailRepository
            .getPendingReadStateActionSourceUids(
                account.id(), inbox.remoteName(), inbox.uidValidity())
            .size());

    ImapStoreSync.syncImapFolders(account, store, folderRepository, emailRepository);

    EmailPage afterResync =
        emailRepository.getMessages(
            inbox.id(), 20, Long.MAX_VALUE, 0, PageDirection.RIGHT, SortOrder.DESCENDING);
    for (Email email : afterResync.emails()) {
      assertTrue(email.header().read(), "message uid " + email.header().imapUid());
    }
    assertEquals(0, afterResync.emails().stream().filter(email -> !email.header().read()).count());

    Store verifyStore = GreenmailUtils.getImapStore(greenMail);
    Folder imapInbox = verifyStore.getFolder("INBOX");
    imapInbox.open(Folder.READ_ONLY);
    try {
      for (Message message : imapInbox.getMessages()) {
        assertFalse(message.isSet(Flags.Flag.SEEN));
      }
    } finally {
      imapInbox.close();
      verifyStore.close();
    }
  }

  private static Email syncOnlyMessage() throws MessagingException {
    Store store = GreenmailUtils.getImapStore(greenMail);
    InMemoryFolderRepository folderRepository = new InMemoryFolderRepository();
    InMemoryEmailRepository messageRepository = new InMemoryEmailRepository();
    Account account = GreenmailUtils.getAccount();

    ImapStoreSync.syncImapFolders(account, store, folderRepository, messageRepository);

    NamedFolder inbox = folderRepository.getFolders(account.id()).getFirst();
    assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
    long storedUid = messageRepository.getAllMessageIds(inbox.id()).iterator().next();
    return messageRepository.findByFolderAndUid(inbox.id(), storedUid).orElseThrow();
  }

  private static MimeMessage createBaseMessage(String subject) throws MessagingException {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress("from@foo.bar"));
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("to@foo.bar"));
    message.setSubject(subject, StandardCharsets.UTF_8.name());
    return message;
  }

  private static void deliver(MimeMessage message) {
    greenMail.setUser(TEST_ADDRESS, TEST_USERNAME, TEST_PASSWORD).deliver(message);
  }
}
