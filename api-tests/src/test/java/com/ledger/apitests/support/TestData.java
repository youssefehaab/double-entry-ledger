package com.ledger.apitests.support;

import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test-data builders and small helpers shared across the suite. Deliberately dependency-free
 * (plain maps -> JSON) so this module never needs to depend on the main app's DTO classes -
 * this suite treats the app purely as a black box reachable over HTTP.
 */
public final class TestData {

    private TestData() {
    }

    public static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static String randomIdempotencyKey() {
        return "itest-" + UUID.randomUUID();
    }

    /** Creates an ASSET account named "Test Account <suffix>" in USD and returns its id. */
    public static String createAccount() {
        return createAccount("Test Account " + uniqueSuffix(), "USD", "ASSET");
    }

    public static String createAccount(String name, String currency, String accountType) {
        Response response = given()
                .spec(ApiClient.spec())
                .contentType("application/json")
                .body(accountRequest(name, currency, accountType))
                .post("/accounts");
        assertThat("account creation must succeed for valid input in test setup",
                response.statusCode(), is(201));
        return response.jsonPath().getString("id");
    }

    public static Map<String, Object> accountRequest(String name, String currency, String accountType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("currency", currency);
        body.put("accountType", accountType);
        return body;
    }

    public static Map<String, Object> entry(String accountId, Object amount, String direction) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("accountId", accountId);
        e.put("amount", amount);
        e.put("direction", direction);
        return e;
    }

    public static Map<String, Object> transactionRequest(String description, List<Map<String, Object>> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("entries", entries);
        return body;
    }

    /** A simple balanced two-leg transaction: DEBIT `from`, CREDIT `to`, same amount. */
    public static Map<String, Object> simpleTransfer(String from, String to, Object amount, String description) {
        return transactionRequest(description, List.of(
                entry(from, amount, "DEBIT"),
                entry(to, amount, "CREDIT")
        ));
    }

    public static Response postTransaction(String idempotencyKey, Map<String, Object> body) {
        return given()
                .spec(ApiClient.spec())
                .contentType("application/json")
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .post("/transactions");
    }
}
