package com.ledger.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.EntryRequest;
import com.ledger.service.api.dto.EntryResponse;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyRequestMatcherTest {

    private final UUID cashAccountId = UUID.randomUUID();
    private final UUID revenueAccountId = UUID.randomUUID();

    private TransactionResponse existing(String description, List<EntryResponse> entries) {
        return new TransactionResponse(
                UUID.randomUUID(), description, TransactionStatus.POSTED, Instant.now(), Instant.now(), entries);
    }

    private EntryResponse entryResponse(UUID accountId, BigDecimal amount, EntryDirection direction) {
        return new EntryResponse(UUID.randomUUID(), UUID.randomUUID(), accountId, amount, direction, Instant.now());
    }

    private EntryRequest entryRequest(UUID accountId, BigDecimal amount, EntryDirection direction) {
        return new EntryRequest(accountId, amount, direction);
    }

    @Test
    void matches_whenDescriptionAndEntriesAreIdentical() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isTrue();
    }

    @Test
    void matches_whenEntriesAreInADifferentOrder() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        // Same entries, reversed order in the retried request.
        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT),
                entryRequest(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isTrue();
    }

    @Test
    void matches_whenAmountScaleDiffersButValueIsEqual() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(cashAccountId, new BigDecimal("100"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("100.0"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isTrue();
    }

    @Test
    void doesNotMatch_whenDescriptionDiffers() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #2 - different", List.of(
                entryRequest(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isFalse();
    }

    @Test
    void doesNotMatch_whenAmountDiffers() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(cashAccountId, new BigDecimal("200.00"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("200.00"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isFalse();
    }

    @Test
    void doesNotMatch_whenDirectionDiffers() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(cashAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT),
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isFalse();
    }

    @Test
    void doesNotMatch_whenAccountDiffers() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(UUID.randomUUID(), new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isFalse();
    }

    @Test
    void doesNotMatch_whenNumberOfEntriesDiffers() {
        TransactionResponse existing = existing("Invoice #1", List.of(
                entryResponse(cashAccountId, new BigDecimal("100.00"), EntryDirection.DEBIT),
                entryResponse(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        CreateTransactionRequest request = new CreateTransactionRequest("Invoice #1", List.of(
                entryRequest(cashAccountId, new BigDecimal("50.00"), EntryDirection.DEBIT),
                entryRequest(cashAccountId, new BigDecimal("50.00"), EntryDirection.DEBIT),
                entryRequest(revenueAccountId, new BigDecimal("100.00"), EntryDirection.CREDIT)));

        assertThat(IdempotencyRequestMatcher.matches(existing, request)).isFalse();
    }
}
