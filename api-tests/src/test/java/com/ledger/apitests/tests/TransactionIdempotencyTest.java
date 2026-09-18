package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Double-Entry Ledger API")
@Feature("POST /transactions - Idempotency-Key handling")
class TransactionIdempotencyTest {

    private String accountA;
    private String accountB;

    @BeforeEach
    void setUp() {
        accountA = createAccount();
        accountB = createAccount();
    }

    @Test
    @Story("Idempotent replay")
    @Description("First POST with a fresh Idempotency-Key creates (201); replaying the identical key+body "
            + "returns 200 with the SAME transaction id and same entry ids, and does not create a duplicate "
            + "(verified via GET /transactions/{id} and entry counts before/after)")
    void identicalReplayReturns200WithSameTransactionNoDuplicate() {
        String key = randomIdempotencyKey();
        Map<String, Object> body = simpleTransfer(accountA, accountB, 10, "idempotent replay test");

        Response first = postTransaction(key, body);
        first.then().statusCode(201);
        String txId = first.jsonPath().getString("id");
        List<String> firstEntryIds = first.jsonPath().getList("entries.id", String.class);

        long entryCountBefore = entryCount(accountA);

        Response replay = postTransaction(key, body);
        replay.then().statusCode(200);

        assertThat("replay must return the SAME transaction id as the original",
                replay.jsonPath().getString("id"), equalTo(txId));
        assertThat("replay must return the SAME entry ids as the original (no new entries minted)",
                replay.jsonPath().getList("entries.id", String.class), equalTo(firstEntryIds));

        long entryCountAfter = entryCount(accountA);
        assertThat("replaying an idempotency key must not create additional entries",
                entryCountAfter, equalTo(entryCountBefore));

        // Cross-check against GET /transactions/{id} - independently confirms no duplicate was created
        // under a different id, and that the stored transaction matches what both POST responses returned.
        Response fetched = given().spec(ApiClient.spec()).get("/transactions/{id}", txId);
        fetched.then().statusCode(200).body("id", equalTo(txId));
        assertThat(new BigDecimal(fetched.jsonPath().getString("entries[0].amount"))
                        .compareTo(new BigDecimal("10")),
                equalTo(0));
    }

    @Test
    @Story("Idempotent replay")
    @Description("Replaying the same key with a DIFFERENT request body returns 409, and the original "
            + "transaction is left untouched")
    void replayWithDifferentBodyReturns409AndLeavesOriginalIntact() {
        String key = randomIdempotencyKey();
        Response first = postTransaction(key, simpleTransfer(accountA, accountB, 10, "original"));
        first.then().statusCode(201);
        String originalTxId = first.jsonPath().getString("id");

        Response conflict = postTransaction(key, simpleTransfer(accountA, accountB, 11, "different body"));
        conflict.then()
                .statusCode(409)
                .body("status", equalTo(409))
                .body("messages", hasItem(containsString(key)));

        // Original transaction must be unaffected by the rejected conflicting replay.
        given().spec(ApiClient.spec()).get("/transactions/{id}", originalTxId)
                .then().statusCode(200)
                .body("description", equalTo("original"));
    }

    @Test
    @Story("Idempotent replay")
    @Description("A different Idempotency-Key with the same body creates a genuinely new, second transaction")
    void differentKeySameBodyCreatesSecondTransaction() {
        Map<String, Object> body = simpleTransfer(accountA, accountB, 5, "same body different key");
        Response first = postTransaction(randomIdempotencyKey(), body);
        Response second = postTransaction(randomIdempotencyKey(), body);

        first.then().statusCode(201);
        second.then().statusCode(201);
        assertThat(first.jsonPath().getString("id"), not(equalTo(second.jsonPath().getString("id"))));
    }

    @Test
    @Story("Header validation")
    @Description("Missing Idempotency-Key header returns 400")
    void missingHeaderReturns400() {
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(simpleTransfer(accountA, accountB, 1, "no header"))
                .post("/transactions");
        response.then().statusCode(400).body("status", equalTo(400));
    }

    @Test
    @Story("Header validation")
    @Description("Blank (whitespace-only) Idempotency-Key header returns 400. NOTE: the live app takes a "
            + "different validation code path for a whitespace-only header ('...must not be blank', a "
            + "bean-validation message) than for a truly empty/absent header ('Idempotency-Key header is "
            + "required', a controller-level check) - both correctly 400, but with two different messages "
            + "for what a caller would consider the same 'blank' case. See final report.")
    void blankHeaderReturns400() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", "   ")
                .body(simpleTransfer(accountA, accountB, 1, "blank header"))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(anyOf(containsStringIgnoringCase("required"), containsStringIgnoringCase("blank"))));
    }

    @Test
    @Story("Header validation")
    @Description("Idempotency-Key header longer than 255 characters returns 400")
    void oversizedHeaderReturns400() {
        String tooLong = "a".repeat(256);
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", tooLong)
                .body(simpleTransfer(accountA, accountB, 1, "oversized header"))
                .post("/transactions")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("255")));
    }

    @Test
    @Story("Header validation")
    @Description("Idempotency-Key header of exactly 255 characters (the documented maxLength boundary) is accepted")
    void exactly255CharHeaderIsAccepted() {
        String exactly255 = "a".repeat(255);
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", exactly255)
                .body(simpleTransfer(accountA, accountB, 1, "255-char header"))
                .post("/transactions")
                .then()
                .statusCode(201);
    }

    @Test
    @Story("Header validation")
    @Description("A single-character Idempotency-Key (minLength: 0 boundary is really about blank-ness, not "
            + "length) is accepted")
    void singleCharHeaderIsAccepted() {
        given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", "x")
                .body(simpleTransfer(accountA, accountB, 1, "1-char header"))
                .post("/transactions")
                .then()
                .statusCode(201);
    }

    private static long entryCount(String accountId) {
        return given().spec(ApiClient.spec())
                .queryParam("page", 0)
                .queryParam("size", 1)
                .get("/accounts/{id}/entries", accountId)
                .then().extract().jsonPath().getLong("totalElements");
    }
}
