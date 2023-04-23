package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.repository.DbAccountRepository;

import java.util.List;

public class AccountService {
    private final DbAccountRepository accountRepository;

    public AccountService(DbAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> getAccounts() {
        return accountRepository.getAccounts();
    }

    public Object getAccount(int accountId) {
        return accountRepository.getAccount(accountId);
    }
}
