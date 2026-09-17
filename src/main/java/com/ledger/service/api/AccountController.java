package com.ledger.service.api;

import com.ledger.service.api.dto.AccountResponse;
import com.ledger.service.api.dto.BalanceResponse;
import com.ledger.service.api.dto.CreateAccountRequest;
import com.ledger.service.api.dto.ErrorResponse;
import com.ledger.service.api.dto.PagedEntriesResponse;
import com.ledger.service.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Chart-of-accounts and derived account state (balance, entries)")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Create an account",
            description = "Not idempotent: retrying this request creates a second, distinct account "
                    + "(see AccountService javadoc). There is no client-supplied idempotency key for "
                    + "account creation in this phase.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed (blank name, malformed currency, unknown accountType)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "415", description = "Content-Type is not application/json",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get an account's current balance",
            description = "Always computed on the fly by summing the account's entries - there is no "
                    + "stored balance column. Sign convention: DEBIT entries add to the balance, CREDIT "
                    + "entries subtract from it (\"debit-positive\"), independent of account_type.")
    @ApiResponse(responseCode = "200", description = "Balance computed")
    @ApiResponse(responseCode = "400", description = "{id} is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No account with this id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public BalanceResponse getBalance(@PathVariable UUID id) {
        return accountService.getBalance(id);
    }

    @GetMapping("/{id}/entries")
    @Operation(summary = "List an account's entries, paginated",
            description = "Ordered by created_at then id (stable/deterministic pagination). "
                    + "Only page/size are honored from the query string; sort order is fixed.")
    @ApiResponse(responseCode = "200", description = "Page of entries")
    @ApiResponse(responseCode = "400", description = "{id} is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No account with this id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PagedEntriesResponse getEntries(
            @PathVariable UUID id,
            @Parameter(description = "Zero-based page index and page size")
            @PageableDefault(size = 20) Pageable pageable) {
        return accountService.getEntries(id, pageable);
    }
}
