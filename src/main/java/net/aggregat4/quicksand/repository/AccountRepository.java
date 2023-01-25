package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Account;

import javax.sql.DataSource;
import java.util.List;

public class AccountRepository {

    private final DataSource ds;

    public AccountRepository(DataSource ds) {
        this.ds = ds;
    }

    public List<Account> getAccounts() {
        return List.of(new Account(1, "foo@example.com"), new Account(2, "bar@example.org"));
    }
}
