package com.ledger.service.service.exception;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when an account referenced by an API request does not exist.
 *
 * <p>Judgment call: this maps to HTTP 404, not 422, even when the missing
 * account is referenced from inside a {@code POST /transactions} request
 * body (rather than a path variable). Rationale: 422 is reserved in this
 * API specifically for "the request is well-formed and every referenced
 * resource exists, but the entries as a whole violate the double-entry
 * balance invariant" (see {@link UnbalancedTransactionException}) - a
 * semantic rule about the relationship between entries. "This account_id
 * does not exist" is a simpler, more fundamental problem: a reference to a
 * resource that is not there, which is exactly what 404 conventionally
 * means, whether the id came from a path segment or a request body field.
 * Keeping that mapping consistent (any missing account -&gt; 404, anywhere it
 * is referenced) avoids the same failure mode meaning different things in
 * different endpoints.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("account not found: " + accountId);
    }

    public AccountNotFoundException(List<UUID> accountIds) {
        super("account(s) not found: " + accountIds);
    }
}
