package net.aggregat4.quicksand.greenmail;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import net.aggregat4.quicksand.domain.Account;

public class GreenmailUtils {

    private static final String USERNAME = "testuser";
    private static final String PASSWORD = "testpassword";
    private static final Account account = new Account(1, "test", "localhost", 1234, USERNAME, PASSWORD, "localhost", 25, USERNAME, PASSWORD);
    private static final String EMAIL = "testuser@localhost";

    public static Store getImapStore(GreenMailOperations greenMail) throws MessagingException {
        Session imapSession = greenMail.getImap().createSession();
        Store store = imapSession.getStore("imap");
        store.connect(USERNAME, PASSWORD);
        return store;
    }

    public static void deliverOneMessage(GreenMailOperations greenMail, String subject, String body, String from, String to) {
        deliverMessages(greenMail, subject, body, from, to, 200);
    }

    public static void deliverMessages(GreenMailOperations greenMail, String subject, String body, String from, String to, int count) {
        MimeMessage message = GreenMailUtil.createTextEmail(to, from, subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
        GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
        for (int i = 0; i < count; i++) {
            user.deliver(message);
        }
    }

    public static void deliverMessages(GreenMailOperations greenMail, int count) {
        String subject = GreenMailUtil.random();
        String body = GreenMailUtil.random();
        String from = GreenMailUtil.random() + "@example.com";
        String to = "foo@bar.com";
        GreenMailUser user = greenMail.setUser(EMAIL, USERNAME, PASSWORD);
        for (int i = 0; i < count; i++) {
            MimeMessage message = GreenMailUtil.createTextEmail(to, from, subject, body, greenMail.getSmtp().getServerSetup()); // Construct message
            user.deliver(message);
        }
    }

    public static Account getAccount() {
        return account;
    }

}
