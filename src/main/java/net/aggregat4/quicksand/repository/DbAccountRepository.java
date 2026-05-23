package net.aggregat4.quicksand.repository;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.security.AccountCredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DbAccountRepository implements AccountRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(DbAccountRepository.class);

  private final DataSource ds;
  private final AccountCredentialCipher credentialCipher;

  public DbAccountRepository(DataSource ds) {
    this(ds, AccountCredentialCipher.load());
  }

  public DbAccountRepository(DataSource ds, AccountCredentialCipher credentialCipher) {
    this.ds = ds;
    this.credentialCipher = credentialCipher;
  }

  @Override
  public List<Account> getAccounts() {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT * FROM accounts",
        stmt ->
            DbUtil.withResultSetFunction(
                stmt,
                rs -> {
                  List<Account> accounts = new ArrayList<>();
                  while (rs.next()) {
                    accounts.add(readAccount(rs));
                  }
                  return accounts;
                }));
  }

  @Override
  public void createAccountIfNew(Account account) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        """
                    INSERT OR IGNORE INTO accounts (name, imap_host, imap_port, imap_username, imap_password, smtp_host, smtp_port, smtp_username, smtp_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
        stmt -> {
          stmt.setString(1, account.name());
          stmt.setString(2, account.imapHost());
          stmt.setInt(3, account.imapPort());
          stmt.setString(4, account.imapUsername());
          stmt.setString(5, credentialCipher.encrypt(account.imapPassword()));
          stmt.setString(6, account.smtpHost());
          stmt.setInt(7, account.smtpPort());
          stmt.setString(8, account.smtpUsername());
          stmt.setString(9, credentialCipher.encrypt(account.smtpPassword()));
          int affectedRows = stmt.executeUpdate();
          if (affectedRows == 0) {
            LOGGER.debug("Account {} already existed", account.name());
          }
        });
  }

  @Override
  public Account getAccount(int accountId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT * FROM accounts WHERE id = ?",
        stmt -> {
          stmt.setInt(1, accountId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (rs.next()) {
                  return readAccount(rs);
                }
                throw new IllegalStateException("No account with id %d".formatted(accountId));
              });
        });
  }

  /** Encrypts legacy plaintext passwords still stored in SQLite. */
  public int reencryptLegacyCredentials() {
    int updated =
        DbUtil.withPreparedStmtFunction(
            ds,
            "SELECT id, imap_password, smtp_password FROM accounts",
            stmt ->
                DbUtil.withResultSetFunction(
                    stmt,
                    rs -> {
                      int count = 0;
                      while (rs.next()) {
                        int accountId = rs.getInt(1);
                        String imapStored = rs.getString(2);
                        String smtpStored = rs.getString(3);
                        if (!credentialCipher.needsEncryption(imapStored)
                            && !credentialCipher.needsEncryption(smtpStored)) {
                          continue;
                        }
                        updateStoredPasswords(
                            accountId, encryptIfNeeded(imapStored), encryptIfNeeded(smtpStored));
                        count++;
                      }
                      return count;
                    }));
    if (updated > 0) {
      LOGGER.info("Encrypted plaintext credentials for {} account(s)", updated);
    }
    return updated;
  }

  private String encryptIfNeeded(String stored) {
    return credentialCipher.needsEncryption(stored) ? credentialCipher.encrypt(stored) : stored;
  }

  private void updateStoredPasswords(int accountId, String imapPassword, String smtpPassword) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE accounts SET imap_password = ?, smtp_password = ? WHERE id = ?",
        stmt -> {
          stmt.setString(1, imapPassword);
          stmt.setString(2, smtpPassword);
          stmt.setInt(3, accountId);
          stmt.executeUpdate();
        });
  }

  private Account readAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Account(
        rs.getInt(1),
        rs.getString(2),
        rs.getString(3),
        rs.getInt(4),
        rs.getString(5),
        credentialCipher.decrypt(rs.getString(6)),
        rs.getString(7),
        rs.getInt(8),
        rs.getString(9),
        credentialCipher.decrypt(rs.getString(10)));
  }
}
