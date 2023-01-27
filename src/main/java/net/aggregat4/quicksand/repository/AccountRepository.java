package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Account;

import javax.sql.DataSource;
import java.util.List;

public class AccountRepository {

    public static final Account ACCOUNT1 = new Account(
            1,
            "foo@example.com",
            "imap-host",
            "imap-username",
            "imap-password",
            "smtp-host",
            "smtp-username",
            "smtp-password");
    public final static Account ACCOUNT2 = new Account(2, "bar@example.org",
                    "imap-host",
                    "imap-username",
                    "imap-password",
                    "smtp-host",
                    "smtp-username",
                    "smtp-password");
    private final DataSource ds;

    public AccountRepository(DataSource ds) {
        this.ds = ds;
    }

    public List<Account> getAccounts() {
        return List.of(ACCOUNT2, ACCOUNT2);
    }
}
