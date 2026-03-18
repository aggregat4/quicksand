package net.aggregat4.quicksand;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import net.aggregat4.quicksand.greenmail.GreenmailUtils;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.SortTerm;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ImapRetrievalTest {

    @RegisterExtension
    static GreenMailExtension greenMail = GreenmailTestUtils.configureTestGreenMailExtension();

    @Test
    public void retrieveFirst3Emails() throws MessagingException {
        String subject = GreenMailUtil.random();
        String body = GreenMailUtil.random();
        GreenmailUtils.deliverMessages(greenMail, subject, body, "from@foo.bar", "to@foo.bar", 3);
        IMAPStore store = (IMAPStore) GreenmailUtils.getImapStore(greenMail);
        System.out.println("Server has CONDSTORE? " + store.hasCapability("CONDSTORE"));
        System.out.println("Server has QRESYNC? " + store.hasCapability("QRESYNC"));
        System.out.println("Server has IDLE? " + store.hasCapability("IDLE"));
        Folder defaultFolder = store.getFolder("INBOX");
        System.out.printf("Name: %s%n", defaultFolder.getName());
        System.out.printf("Full Name: %s%n", defaultFolder.getFullName());
        System.out.printf("URL: %s%n", defaultFolder.getURLName());
        if (defaultFolder instanceof IMAPFolder imapFolder) {
            System.out.printf("IMAP Folder Attributes: %s%n", Arrays.asList(imapFolder.getAttributes()));
            // NOTE: we need to open the folder in a certain mode
            imapFolder.open(Folder.READ_ONLY);
            Message[] sortedMessages = imapFolder.getSortedMessages(new SortTerm[]{SortTerm.DATE});
            assertEquals(3, sortedMessages.length);
            for (Message message: sortedMessages) {
                System.out.printf("Message From: '%s', To: '%s', Subject: '%s' %n", Arrays.stream(message.getFrom()).toList(), Arrays.stream(message.getAllRecipients()).toList(), message.getSubject());
                assertEquals(subject, message.getSubject());
            }
        }
        store.close();
    }
}
