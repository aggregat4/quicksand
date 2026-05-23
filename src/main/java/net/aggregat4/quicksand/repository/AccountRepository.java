package net.aggregat4.quicksand.repository;

import java.util.List;
import net.aggregat4.quicksand.domain.Account;

public interface AccountRepository {
  List<Account> getAccounts();

  /** Only create the account if it does not already exist, otherwise just ignores it. */
  void createAccountIfNew(Account account);

  Account getAccount(int accountId);
}
