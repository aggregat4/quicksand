package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

public class AccountRepository {

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
    public final static Account ACCOUNT2 = new Account(2, "bar@example.org",
            "imap-host",
            143,
            "imap-username",
            "imap-password",
            "smtp-host",
            25,
            "smtp-username",
            "smtp-password");
    private final DataSource ds;

    public AccountRepository(DataSource ds) {
        this.ds = ds;
    }

    public List<Account> getAccounts() {
        return DbUtil.withPreparedStmtFunction(
                ds,
                "SELECT * FROM accounts",
                stmt -> DbUtil.withResultSetFunction(stmt, rs -> {
                    List<Account> accounts = new ArrayList<>();
                    while (rs.next()) {
                        accounts.add(new Account(
                                rs.getInt(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getInt(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getInt(8),
                                rs.getString(9),
                                rs.getString(10)));
                    }
                    return accounts;
                }));
    }
}
