package net.aggregat4.quicksand.webservice;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.NamedFolder;

import java.util.List;

public class MockAccountData {

    public final List<NamedFolder> NAMED_FOLDERS = List.of(new NamedFolder(1, "INBOX", 0), new NamedFolder(2, "Archive", 0), new NamedFolder(3, "Sent", 0), new NamedFolder(4, "Junk", 0));
    public final static Account ACCOUNT2 = new Account(2, "bar@example.org",
            "imap-host",
            143,
            "imap-username",
            "imap-password",
            "smtp-host",
            25,
            "smtp-username",
            "smtp-password");
    public static final Account ACCOUNT1 = new Account(
            1,
            "foo@example.com",
            "imap-host",
            143,
            "imap-username",
            "imap-password",
            "smtp-host",
            25,
            "smtp-username",
            "smtp-password");
    public final List<Account> ACCOUNTS = List.of(
            ACCOUNT1,
            ACCOUNT2);

}
