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
import net.aggregat4.quicksand.GreenmailTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
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
    assertEquals(1, messageRepository.getAllMessageIds(inbox.id()).size());
    long storedUid = messageRepository.getAllMessageIds(inbox.id()).iterator().next();
    assertTrue(storedUid > 0);
    Email email = messageRepository.findByMessageUid(storedUid).orElseThrow();
    assertEquals(subject, email.header().subject());
    assertTrue(email.plainText());
    assertTrue(email.body().contains(body));
    assertTrue(email.header().bodyExcerpt().contains(body));
    // TODO: attachment handling
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
    email = messageRepository.findByMessageUid(updatedStoredUid).orElseThrow();
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
    return messageRepository.findByMessageUid(storedUid).orElseThrow();
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
