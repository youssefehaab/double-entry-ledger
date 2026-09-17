package com.ledger.service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.service.api.dto.EntryRequest;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.service.exception.UnbalancedTransactionException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionBalanceValidatorTest {

    private static EntryRequest entry(BigDecimal amount, EntryDirection direction) {
        return new EntryRequest(UUID.randomUUID(), amount, direction);
    }

    @Test
    void doesNotThrow_whenDebitsEqualCredits() {
        List<EntryRequest> entries = List.of(
                entry(new BigDecimal("100.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("100.00"), EntryDirection.CREDIT));

        assertThatCode(() -> TransactionBalanceValidator.validateBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrow_whenMultipleEntriesSumToBalance() {
        List<EntryRequest> entries = List.of(
                entry(new BigDecimal("60.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("40.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("100.00"), EntryDirection.CREDIT));

        assertThatCode(() -> TransactionBalanceValidator.validateBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrow_whenAmountsHaveDifferentScaleButEqualValue() {
        // 10.00 and 10.0 and 10 are numerically equal even though BigDecimal.equals()
        // would treat them as unequal (different scale) - the validator must use
        // compareTo semantics, not equals semantics.
        List<EntryRequest> entries = List.of(
                entry(new BigDecimal("10.0"), EntryDirection.DEBIT),
                entry(new BigDecimal("10.00"), EntryDirection.CREDIT));

        assertThatCode(() -> TransactionBalanceValidator.validateBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void throws_whenDebitsExceedCredits() {
        List<EntryRequest> entries = List.of(
                entry(new BigDecimal("100.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("40.00"), EntryDirection.CREDIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("100.00")
                .hasMessageContaining("40.00");
    }

    @Test
    void throws_whenCreditsExceedDebits() {
        List<EntryRequest> entries = List.of(
                entry(new BigDecimal("40.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("100.00"), EntryDirection.CREDIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void throws_whenOnlyASingleEntryIsGiven() {
        // A lone entry can never balance - there is nothing offsetting it.
        List<EntryRequest> entries = List.of(entry(new BigDecimal("25.00"), EntryDirection.DEBIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void throws_whenEntryListIsEmpty() {
        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(List.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }
}
