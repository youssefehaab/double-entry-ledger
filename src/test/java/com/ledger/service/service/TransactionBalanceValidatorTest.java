package com.ledger.service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.domain.Transaction;
import com.ledger.service.service.exception.UnbalancedTransactionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * As of multi-currency support (Phase 1 of v1 -&gt; v1.1), {@link
 * TransactionBalanceValidator} validates already-FX-converted {@link Entry}
 * objects (summing {@code baseCurrencyAmount}), not raw request amounts -
 * see that class's javadoc for why. This test builds {@link Entry}
 * instances directly; {@code amount}/{@code baseCurrencyAmount} are set to
 * the same value in every case here (the identity/same-currency case),
 * which exercises the validator's arithmetic exactly the same way a
 * same-currency posting through the real app would.
 */
class TransactionBalanceValidatorTest {

    private static final Transaction DUMMY_TRANSACTION = new Transaction("dummy-key", "dummy");
    private static final Account DUMMY_ACCOUNT = new Account("Dummy", "USD", AccountType.ASSET);

    private static Entry entry(BigDecimal amount, EntryDirection direction) {
        return new Entry(DUMMY_TRANSACTION, DUMMY_ACCOUNT, amount, direction, amount, BigDecimal.ONE, Instant.now());
    }

    @Test
    void doesNotThrow_whenDebitsEqualCredits() {
        List<Entry> entries = List.of(
                entry(new BigDecimal("100.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("100.00"), EntryDirection.CREDIT));

        assertThatCode(() -> TransactionBalanceValidator.validateBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrow_whenMultipleEntriesSumToBalance() {
        List<Entry> entries = List.of(
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
        List<Entry> entries = List.of(
                entry(new BigDecimal("10.0"), EntryDirection.DEBIT),
                entry(new BigDecimal("10.00"), EntryDirection.CREDIT));

        assertThatCode(() -> TransactionBalanceValidator.validateBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void throws_whenDebitsExceedCredits() {
        List<Entry> entries = List.of(
                entry(new BigDecimal("100.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("40.00"), EntryDirection.CREDIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("100.00")
                .hasMessageContaining("40.00");
    }

    @Test
    void throws_whenCreditsExceedDebits() {
        List<Entry> entries = List.of(
                entry(new BigDecimal("40.00"), EntryDirection.DEBIT),
                entry(new BigDecimal("100.00"), EntryDirection.CREDIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void throws_whenOnlyASingleEntryIsGiven() {
        // A lone entry can never balance - there is nothing offsetting it.
        List<Entry> entries = List.of(entry(new BigDecimal("25.00"), EntryDirection.DEBIT));

        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(entries))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void throws_whenEntryListIsEmpty() {
        assertThatThrownBy(() -> TransactionBalanceValidator.validateBalanced(List.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }
}
