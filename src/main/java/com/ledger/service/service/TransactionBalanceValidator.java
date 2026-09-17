package com.ledger.service.service;

import com.ledger.service.api.dto.EntryRequest;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.service.exception.UnbalancedTransactionException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Pure, stateless, app-level pre-check that a requested set of entries
 * balances (sum(DEBIT) == sum(CREDIT)) - deliberately a plain static method
 * with no Spring/DB dependency so it can be unit tested directly and runs
 * before any DB write is attempted (see
 * {@link com.ledger.service.service.exception.UnbalancedTransactionException}
 * for why this is not the only enforcement of the invariant).
 *
 * <p>Uses {@link BigDecimal#compareTo(BigDecimal)}, not {@code equals}, to
 * compare totals: {@code compareTo} treats 10.00 and 10.0 (different scale,
 * same numeric value) as equal, which is the correct notion of "balanced"
 * here; {@code equals} would wrongly treat them as different.
 */
public final class TransactionBalanceValidator {

    private TransactionBalanceValidator() {
    }

    public static void validateBalanced(List<EntryRequest> entries) {
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

        for (EntryRequest entry : entries) {
            if (entry.direction() == EntryDirection.DEBIT) {
                debitTotal = debitTotal.add(entry.amount());
            } else {
                creditTotal = creditTotal.add(entry.amount());
            }
        }

        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new UnbalancedTransactionException(debitTotal, creditTotal);
        }
    }
}
