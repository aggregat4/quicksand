package net.aggregat4.quicksand;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.SortTerm;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class ImapRetrievalTest {

    @Test
    public void retrieveFirst3Emails() throws MessagingException {
        Properties props = new Properties();
        String host = System.getenv("IMAP_HOST");
        String user = System.getenv("IMAP_USER");
        String password = System.getenv("IMAP_PASSWORD");
        Session session = Session.getInstance(props, null);
        Store store = session.getStore("imap");
        store.connect(host, 3143, user, password);
        Folder defaultFolder = store.getFolder("INBOX");
        System.out.printf("Name: %s%n", defaultFolder.getName());
        System.out.printf("Full Name: %s%n", defaultFolder.getFullName());
        System.out.printf("URL: %s%n", defaultFolder.getURLName());
        if (defaultFolder instanceof IMAPFolder imapFolder) {
            System.out.printf("IMAP Folder Attributes: %s%n", List.of(imapFolder.getAttributes()));
            // NOTE: we need to open the folder in a certain mode
            imapFolder.open(Folder.READ_ONLY);
            Message[] sortedMessages = imapFolder.getSortedMessages(new SortTerm[]{SortTerm.DATE});
            for (Message message: sortedMessages) {
                System.out.printf("Message From: '%s', To: '%s', Subject: '%s' %n", Arrays.stream(message.getFrom()).toList(), Arrays.stream(message.getAllRecipients()).toList(), message.getSubject());
            }
        }
        store.close();
    }
}
