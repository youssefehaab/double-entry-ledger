package com.ledger.service.api;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.ErrorResponse;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.service.TransactionOutcome;
import com.ledger.service.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Judgment calls documented here (see also {@code TransactionService}
 * javadoc for the full idempotency mechanism):
 * <ul>
 *   <li><b>Idempotency-Key is required.</b> A missing header is rejected
 *       with HTTP 400 (Spring's {@code MissingRequestHeaderException},
 *       mapped in {@code GlobalExceptionHandler}) before any validation or
 *       processing of the body. This endpoint moves money between
 *       accounts, is meant to be safely retried by clients, and has no
 *       other natural dedupe key (unlike, say, a client-generated resource
 *       id) - so the key is not optional.</li>
 *   <li><b>201 for first-time creation, 200 for a replay</b> of the same
 *       key with the same body. Both return the identical
 *       {@link TransactionResponse} body; only the status code differs,
 *       signaling to the client whether this call actually caused the
 *       transaction to be created just now.</li>
 * </ul>
 */
@RestController
@RequestMapping("/transactions")
@Validated
@Tag(name = "Transactions", description = "Posting and retrieving balanced double-entry transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Create and post a balanced transaction",
            description = "Validates that entries balance (422 if not) and that every referenced "
                    + "account exists (404 if not), then atomically creates the transaction (status "
                    + "POSTED) and its entries. Idempotent via the required Idempotency-Key header: "
                    + "replaying the same key with the same body returns the original result (200) "
                    + "without reprocessing; reusing the same key with a different body returns 409.")
    @ApiResponse(responseCode = "201", description = "Transaction created and posted")
    @ApiResponse(responseCode = "200", description = "Idempotent replay: identical request already processed under this key")
    @ApiResponse(responseCode = "400", description = "Missing/blank Idempotency-Key header, or request body validation failed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "One or more referenced account_ids do not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key was already used with a different request body",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Entries do not balance (sum(DEBIT) != sum(CREDIT))",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request,
            @Parameter(description = "Client-supplied key that makes this request safely retryable. "
                    + "Required.", required = true, example = "3f2b9c6e-6f1a-4c9d-9a7b-1e2d3c4b5a6f")
            @RequestHeader(name = "Idempotency-Key")
            @NotBlank(message = "Idempotency-Key header must not be blank")
            @Size(max = 255, message = "Idempotency-Key must be at most 255 characters")
            String idempotencyKey) {
        TransactionOutcome outcome = transactionService.createTransaction(request, idempotencyKey);
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transaction and its entries")
    @ApiResponse(responseCode = "200", description = "Transaction found")
    @ApiResponse(responseCode = "404", description = "No transaction with this id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TransactionResponse getTransaction(@PathVariable UUID id) {
        return transactionService.getTransaction(id);
    }
}
