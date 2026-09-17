package com.ledger.service.repository;

import com.ledger.service.domain.Entry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryRepository extends JpaRepository<Entry, UUID> {

    List<Entry> findByTransactionId(UUID transactionId);

    List<Entry> findByAccountId(UUID accountId);
}
