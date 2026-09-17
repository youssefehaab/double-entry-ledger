package com.ledger.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledger.service.api.dto.AccountResponse;
import com.ledger.service.api.dto.CreateAccountRequest;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
    }

    @Test
    void createAccount_normalizesCurrencyToUppercase() {
        CreateAccountRequest request = new CreateAccountRequest("Cash", "usd", AccountType.ASSET);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        accountService.createAccount(request);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void createAccount_trimsName() {
        CreateAccountRequest request = new CreateAccountRequest("  Cash  ", "USD", AccountType.ASSET);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        accountService.createAccount(request);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Cash");
    }

    @Test
    void createAccount_mapsSavedEntityToResponse() {
        CreateAccountRequest request = new CreateAccountRequest("Cash", "USD", AccountType.ASSET);
        Account saved = new Account("Cash", "USD", AccountType.ASSET);
        when(accountRepository.saveAndFlush(any(Account.class))).thenReturn(saved);

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.name()).isEqualTo(saved.getName());
        assertThat(response.currency()).isEqualTo(saved.getCurrency());
        assertThat(response.accountType()).isEqualTo(AccountType.ASSET);
    }

    @Test
    void createAccount_preservesRequestedAccountType() {
        CreateAccountRequest request = new CreateAccountRequest("Bond Liability", "EUR", AccountType.LIABILITY);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.accountType()).isEqualTo(AccountType.LIABILITY);
        assertThat(response.currency()).isEqualTo("EUR");
    }
}
