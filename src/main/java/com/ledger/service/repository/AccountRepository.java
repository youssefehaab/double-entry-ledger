package com.ledger.service.repository;

import com.ledger.service.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Locks this account's row with {@code SELECT ... FOR UPDATE}
     * ({@link LockModeType#PESSIMISTIC_WRITE}), blocking any other DB
     * transaction that also tries to lock the same row (via this method)
     * until the current transaction commits or rolls back.
     *
     * <p><b>Callers MUST acquire locks across multiple accounts in a
     * fixed, deterministic order - ascending account id - never in
     * request-payload order.</b> See {@code TransactionWriter#lockAccountsInOrder}
     * for the full reasoning and the deadlock scenario this specifically
     * prevents (two transactions referencing the same two accounts with
     * opposite debit/credit roles, each locking in payload order, would
     * deadlock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
