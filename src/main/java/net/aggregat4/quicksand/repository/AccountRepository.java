package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

public class AccountRepository {

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

    /**
     * Only create the account if it does not already exist, otherwise just ignores it.
     */
    public void createAccountIfNew(Account account) {
        DbUtil.withPreparedStmtConsumer(ds, """
                    INSERT OR IGNORE INTO accounts (name, imap_host, imap_port, imap_username, imap_password, smtp_host, smtp_port, smtp_username, smtp_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, stmt -> {
            stmt.setString(1, account.name());
            stmt.setString(2, account.imapHost());
            stmt.setInt(3, account.imapPort());
            stmt.setString(4, account.imapUsername());
            stmt.setString(5, account.imapPassword());
            stmt.setString(6, account.smtpHost());
            stmt.setInt(7, account.smtpPort());
            stmt.setString(8, account.smtpUsername());
            stmt.setString(9, account.smtpPassword());
            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                System.out.println("Account %s already existed".formatted(account));
            }
        });
    }

    public Object getAccount(int accountId) {
        return DbUtil.withPreparedStmtFunction(
                ds,
                "SELECT * FROM accounts WHERE id = ?",
                stmt -> {
                    stmt.setInt(1, accountId);
                    return DbUtil.withResultSetFunction(stmt, rs -> {
                        if (rs.next()) {
                            return new Account(
                                    rs.getInt(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getInt(4),
                                    rs.getString(5),
                                    rs.getString(6),
                                    rs.getString(7),
                                    rs.getInt(8),
                                    rs.getString(9),
                                    rs.getString(10));
                        }
                        throw new IllegalStateException("No account with id %d".formatted(accountId));
                    });
                });
    }
}
