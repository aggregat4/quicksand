package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.AccountRepository;

import java.util.List;

public class AccountService {
    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> getAccounts() {
        return accountRepository.getAccounts();
    }
}
