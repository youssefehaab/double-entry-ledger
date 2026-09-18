package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Epic("Double-Entry Ledger API")
@Feature("POST /transactions - balance invariant and entry validation")
class TransactionBalanceValidationTest {

    private String accountA;
    private String accountB;

    @BeforeEach
    void setUp() {
        accountA = createAccount();
        accountB = createAccount();
    }

    @Test
    @Story("Balance invariant")
    @Description("sum(DEBIT) != sum(CREDIT) returns 422 with an ErrorResponse-shaped body")
    void unbalancedEntriesReturn422() {
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("unbalanced", List.of(
                        entry(accountA, 10, "DEBIT"),
                        entry(accountB, 9, "CREDIT")
                )))
                .post("/transactions");

        response.then()
                .statusCode(422)
                .body("status", equalTo(422))
                .body("error", notNullValue())
                .body("messages", not(empty()));
    }

    @Test
    @Story("Balance invariant")
    @Description("A single one-sided entry (no offsetting entry at all) is rejected as unbalanced with 422")
    void singleOneSidedEntryReturns422() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("one-sided", List.of(
                        entry(accountA, 10, "DEBIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(422);
    }

    @Test
    @Story("Balance invariant")
    @Description("Many small entries that net to zero difference via unusual amounts (3 debits vs 1 credit) "
            + "are accepted (201) - balance is a pure sum invariant, not a 1:1 entry-count requirement")
    void manySmallEntriesThatNetToZeroAreAccepted() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("many small entries", List.of(
                        entry(accountA, new java.math.BigDecimal("0.10"), "DEBIT"),
                        entry(accountA, new java.math.BigDecimal("0.10"), "DEBIT"),
                        entry(accountA, new java.math.BigDecimal("0.10"), "DEBIT"),
                        entry(accountB, new java.math.BigDecimal("0.30"), "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(201)
                .body("entries", hasSize(4));
    }

    @Test
    @Story("Amount validation")
    @Description("Zero-amount entries are rejected with 400 (amount has exclusiveMinimum: 0 per spec, i.e. must be > 0)")
    void zeroAmountEntriesAreRejectedWith400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("zero amount", List.of(
                        entry(accountA, 0, "DEBIT"),
                        entry(accountB, 0, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("positive")));
    }

    @Test
    @Story("Amount validation")
    @Description("Negative amounts are rejected with 400 (amount is documented as unsigned; direction carries sign)")
    void negativeAmountsAreRejectedWith400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("negative amount", List.of(
                        entry(accountA, -10, "DEBIT"),
                        entry(accountB, -10, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("positive")));
    }

    @Test
    @Story("Amount validation")
    @Description("A tiny positive fractional amount (0.0001) just above zero is accepted")
    void verySmallPositiveAmountIsAccepted() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("tiny amount", List.of(
                        entry(accountA, new java.math.BigDecimal("0.0001"), "DEBIT"),
                        entry(accountB, new java.math.BigDecimal("0.0001"), "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(201);
    }

    @Test
    @Story("Account existence")
    @Description("A reference to a nonexistent account_id returns 404 (not 422) - the spec reserves 422 "
            + "specifically for the balance invariant")
    void nonexistentAccountReturns404NotUnbalanced422() {
        String ghost = UUID.randomUUID().toString();
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("ghost account", List.of(
                        entry(ghost, 10, "DEBIT"),
                        entry(accountB, 10, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(404)
                .body("messages", hasItem(containsString(ghost)));
    }

    @Test
    @Story("Account existence")
    @Description("Both legs referencing nonexistent accounts still returns 404, listing account ids")
    void bothAccountsNonexistentReturns404() {
        String ghost1 = UUID.randomUUID().toString();
        String ghost2 = UUID.randomUUID().toString();
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("both ghosts", List.of(
                        entry(ghost1, 10, "DEBIT"),
                        entry(ghost2, 10, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(404);
    }

    @Test
    @Story("Request body validation")
    @Description("Empty entries array is rejected with 400 (minItems: 1)")
    void emptyEntriesArrayReturns400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("empty entries", List.of()))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("empty")));
    }

    @Test
    @Story("Request body validation")
    @Description("Missing description field is rejected with 400")
    void missingDescriptionReturns400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(Map.of("entries", List.of(entry(accountA, 1, "DEBIT"), entry(accountB, 1, "CREDIT"))))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("description")));
    }

    @Test
    @Story("Request body validation")
    @Description("An entry missing its accountId field is rejected with 400")
    void entryMissingAccountIdReturns400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(Map.of("description", "x", "entries", List.of(
                        Map.of("amount", 1, "direction", "DEBIT"),
                        entry(accountB, 1, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("accountId")));
    }

    @Test
    @Story("Request body validation")
    @Description("An entry with a direction value outside {DEBIT, CREDIT} fails body parsing with 400")
    void invalidDirectionEnumReturns400() {
        given()
                .spec(ApiClient.spec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(Map.of("description", "x", "entries", List.of(
                        entry(accountA, 1, "SIDEWAYS"),
                        entry(accountB, 1, "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(400);
    }

    @Test
    @Story("Edge case")
    @Description("Unicode characters in the transaction description round-trip correctly")
    void unicodeDescriptionRoundTrips() {
        String description = "支払い 💰 café résumé " + uniqueSuffix();
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest(description, List.of(
                        entry(accountA, 1, "DEBIT"),
                        entry(accountB, 1, "CREDIT")
                )))
                .post("/transactions");
        response.then().statusCode(201).body("description", equalTo(description));
    }

    @Test
    @Story("Edge case")
    @Description("An implausibly large amount (30-digit number) exceeds the documented supported precision/scale "
            + "(15 integer / 4 fraction digits) and is rejected with 400, matching the documented 400 "
            + "ErrorResponse for this operation")
    void implausiblyLargeAmountReturns400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("huge amount", List.of(
                        entry(accountA, new java.math.BigInteger("99999999999999999999999999999"), "DEBIT"),
                        entry(accountB, new java.math.BigInteger("99999999999999999999999999999"), "CREDIT")
                )))
                .post("/transactions")
                .then()
                .statusCode(400);
    }
}
