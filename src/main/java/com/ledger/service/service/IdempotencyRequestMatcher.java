package com.ledger.service.service;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.EntryResponse;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.domain.EntryDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pure, stateless comparison used to decide whether a {@code POST
 * /transactions} request replays a previous request with the same
 * {@code Idempotency-Key} (same description + same entries, in which case
 * the original result is returned rather than reprocessing) or conflicts
 * with it (different body, same key, in which case the caller must return
 * HTTP 409 - see
 * {@link com.ledger.service.service.exception.IdempotencyKeyConflictException}).
 *
 * <p>No Spring/DB dependency, so this is directly unit testable.
 *
 * <p>Entries are compared as an order-insensitive multiset of
 * (accountId, direction, amount) fingerprints, not the raw request order:
 * a client is not expected to resend entries in exactly the same array
 * order for it to still count as "the same request," and amounts are
 * compared numerically (scale-insensitive, e.g. "10" == "10.00"), matching
 * the same numeric-equality notion used by
 * {@link TransactionBalanceValidator}.
 */
public final class IdempotencyRequestMatcher {

    private IdempotencyRequestMatcher() {
    }

    public static boolean matches(TransactionResponse existing, CreateTransactionRequest request) {
        if (!existing.description().trim().equals(request.description().trim())) {
            return false;
        }
        if (existing.entries().size() != request.entries().size()) {
            return false;
        }

        List<Fingerprint> existingFingerprints = existing.entries().stream()
                .map(Fingerprint::of)
                .sorted(Fingerprint.ORDER)
                .toList();

        List<Fingerprint> requestFingerprints = request.entries().stream()
                .map(Fingerprint::of)
                .sorted(Fingerprint.ORDER)
                .toList();

        return existingFingerprints.equals(requestFingerprints);
    }

    /**
     * Normalized (accountId, direction, amount) tuple. Amount is normalized
     * to a fixed scale (matching the {@code NUMERIC(19,4)} column) and
     * represented as a canonical string so that {@link Object#equals} works
     * correctly for the multiset comparison above - raw
     * {@link BigDecimal#equals(Object)} is scale-sensitive and would treat
     * 10.00 and 10.0000 as different, which is wrong here.
     */
    private record Fingerprint(UUID accountId, EntryDirection direction, String normalizedAmount) {

        static final Comparator<Fingerprint> ORDER = Comparator
                .comparing((Fingerprint f) -> f.accountId)
                .thenComparing(f -> f.direction)
                .thenComparing(f -> f.normalizedAmount);

        static Fingerprint of(EntryResponse entry) {
            return new Fingerprint(entry.accountId(), entry.direction(), normalize(entry.amount()));
        }

        static Fingerprint of(com.ledger.service.api.dto.EntryRequest entry) {
            return new Fingerprint(entry.accountId(), entry.direction(), normalize(entry.amount()));
        }

        private static String normalize(BigDecimal amount) {
            return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
