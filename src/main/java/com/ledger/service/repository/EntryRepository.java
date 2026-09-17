package com.ledger.service.repository;

import com.ledger.service.domain.Entry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntryRepository extends JpaRepository<Entry, UUID> {

    List<Entry> findByTransactionId(UUID transactionId);

    List<Entry> findByAccountId(UUID accountId);

    /**
     * Ordered by created_at then id (tiebreaker for rows created in the same
     * instant, which happens routinely for the multiple entries of a single
     * transaction) so that pagination is stable/deterministic across pages
     * rather than relying on unspecified storage order.
     */
    Page<Entry> findByAccountIdOrderByCreatedAtAscIdAsc(UUID accountId, Pageable pageable);

    /**
     * Computes an account's balance by summing its entries - never read from
     * a stored column, because none exists (see the Account entity/V1
     * migration comment: balances are always derived, never cached).
     *
     * <p>Sign convention (documented here and in {@code BalanceResponse}):
     * DEBIT entries add to the balance, CREDIT entries subtract from it -
     * i.e. a plain "debit-positive" ledger balance, not a per-account-type
     * "normal balance" convention (which would flip the sign for
     * LIABILITY/EQUITY). This keeps the computation a single, unconditional
     * formula independent of account_type, which is simpler and avoids
     * ambiguity given this service does not yet model REVENUE/EXPENSE
     * account types. Callers who want accounting "normal balance" signs can
     * derive them from this value plus the account's accountType.
     *
     * <p>{@code COALESCE(..., 0)} so an account with zero entries yields a
     * zero balance rather than {@code null}.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN e.direction = com.ledger.service.domain.EntryDirection.DEBIT
                     THEN e.amount
                     ELSE -e.amount
                END), 0)
            FROM Entry e
            WHERE e.account.id = :accountId
            """)
    BigDecimal sumSignedAmountsByAccountId(@Param("accountId") UUID accountId);
}
