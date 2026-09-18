package com.ledger.service.service;

import com.ledger.service.domain.Entry;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.service.exception.UnbalancedTransactionException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Pure, stateless, app-level pre-check that a set of (already FX-converted)
 * entries balances in the base currency (sum(DEBIT base_currency_amount) ==
 * sum(CREDIT base_currency_amount)) - deliberately a plain static method
 * with no Spring/DB dependency of its own, so it can be unit tested
 * directly (see {@link com.ledger.service.service.exception.UnbalancedTransactionException}
 * for why this is not the only enforcement of the invariant; the DB-level
 * deferred trigger, V9, is the ultimate backstop).
 *
 * <h2>Why this operates on {@link Entry} (post-FX-conversion), not the raw
 * {@code EntryRequest} amounts from the request body</h2>
 * Before multi-currency support, this validated {@code EntryRequest.amount()}
 * directly, currency-blind, before any DB call - valid only because every
 * entry was implicitly the same currency. That is no longer a correct
 * invariant: a genuinely-balanced cross-currency transaction (e.g. 100 EUR
 * debit against 108 USD credit at a 1.08 rate) has DIFFERENT raw amounts on
 * each leg by design - checking sum(amount) == sum(amount) currency-blind
 * would wrongly reject it. The only value that is meaningfully additive
 * across an entry set with mixed account currencies is
 * {@code base_currency_amount}, which does not exist until each entry's
 * account currency is known and {@code FxRateProvider} has converted it -
 * i.e. not until inside {@code TransactionWriter#createAndPersist}, after
 * accounts are loaded/locked and entries are built, but still strictly
 * before any {@link Entry} row is inserted. This validator is called from
 * there now, not from {@code TransactionService} pre-DB as before - see
 * that method's javadoc.
 *
 * <p>Uses {@link BigDecimal#compareTo(BigDecimal)}, not {@code equals}, to
 * compare totals: {@code compareTo} treats 10.00 and 10.0 (different scale,
 * same numeric value) as equal, which is the correct notion of "balanced"
 * here; {@code equals} would wrongly treat them as different. In practice
 * every {@code base_currency_amount} is already fixed-scale-4 (see
 * DbFxRateProvider), so this mostly matters for defense in depth.
 */
public final class TransactionBalanceValidator {

    private TransactionBalanceValidator() {
    }

    public static void validateBalanced(List<Entry> entries) {
        if (entries.isEmpty()) {
            // Zero entries sums to 0 == 0, which is trivially "balanced" by
            // the arithmetic below, but is not a meaningful double-entry
            // transaction (nothing was posted). Reject explicitly rather
            // than let a no-op transaction through. In practice the
            // CreateTransactionRequest DTO's @NotEmpty already rejects this
            // earlier as a 400; this guard exists so the validator is
            // correct and defensive on its own, independent of the DTO
            // layer that happens to call it.
            throw new UnbalancedTransactionException(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;

        for (Entry entry : entries) {
            if (entry.getDirection() == EntryDirection.DEBIT) {
                debitTotal = debitTotal.add(entry.getBaseCurrencyAmount());
            } else {
                creditTotal = creditTotal.add(entry.getBaseCurrencyAmount());
            }
        }

        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new UnbalancedTransactionException(debitTotal, creditTotal);
        }
    }
}
