package net.aggregat4.quicksand;

import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.Test;

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
        store.connect(host, user, password);
        Folder defaultFolder = store.getFolder("INBOX");
        System.out.printf("Name: %s%n", defaultFolder.getName());
        System.out.printf("Full Name: %s%n", defaultFolder.getFullName());
        System.out.printf("URL: %s%n", defaultFolder.getURLName());
        if (defaultFolder instanceof IMAPFolder) {
            System.out.printf("IMAP Folder Attributes: %s%n", List.of(((IMAPFolder) defaultFolder).getAttributes()));
        }

        store.close();
    }
}
