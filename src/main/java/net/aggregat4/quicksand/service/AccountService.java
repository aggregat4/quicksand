package net.aggregat4.quicksand.service;

import java.util.List;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.AccountRepository;

public class AccountService {
  private final AccountRepository accountRepository;

  public AccountService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  public List<Account> getAccounts() {
    return accountRepository.getAccounts();
  }

  public Account getAccount(int accountId) {
    return accountRepository.getAccount(accountId);
  }
}
