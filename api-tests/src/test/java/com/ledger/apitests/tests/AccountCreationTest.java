package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.ledger.apitests.support.TestData.accountRequest;
import static com.ledger.apitests.support.TestData.uniqueSuffix;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Double-Entry Ledger API")
@Feature("POST /accounts")
class AccountCreationTest {

    @Test
    @Story("Happy path")
    @Description("A valid account request is created (201) and matches the openapi.yaml AccountResponse schema")
    void createsAccountWithValidPayload() {
        String name = "Cash " + uniqueSuffix();
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(accountRequest(name, "USD", "ASSET"))
                .post("/accounts");

        response.then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo(name))
                .body("currency", equalTo("USD"))
                .body("accountType", equalTo("ASSET"))
                .body("createdAt", notNullValue());
    }

    @Test
    @Story("Happy path")
    @Description("Each of the three documented accountType enum values is accepted")
    void acceptsAllDocumentedAccountTypes() {
        for (String type : new String[]{"ASSET", "LIABILITY", "EQUITY"}) {
            Response response = given()
                    .spec(ApiClient.validatedSpec())
                    .contentType("application/json")
                    .body(accountRequest("Acct-" + type + "-" + uniqueSuffix(), "USD", type))
                    .post("/accounts");
            response.then().statusCode(201).body("accountType", equalTo(type));
        }
    }

    @Test
    @Story("Not idempotent")
    @Description("POST /accounts has no idempotency key; repeating the identical request creates a second, distinct account (per spec description)")
    void repeatingIdenticalRequestCreatesDistinctAccounts() {
        Map<String, Object> body = accountRequest("Duplicate-name-" + uniqueSuffix(), "USD", "ASSET");

        Response first = given().spec(ApiClient.spec()).contentType("application/json").body(body).post("/accounts");
        Response second = given().spec(ApiClient.spec()).contentType("application/json").body(body).post("/accounts");

        first.then().statusCode(201);
        second.then().statusCode(201);
        assertThat("two POSTs with an identical body must yield two distinct account ids",
                first.jsonPath().getString("id"), not(equalTo(second.jsonPath().getString("id"))));
    }

    @Test
    @Story("Validation")
    @Description("Blank name is rejected with 400 and a field-level message")
    void rejectsBlankName() {
        given().spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(accountRequest("", "USD", "ASSET"))
                .post("/accounts")
                .then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("error", notNullValue())
                .body("messages", hasItem(containsStringIgnoringCase("name")));
    }

    @Test
    @Story("Validation")
    @Description("Missing all required fields is rejected with 400 listing every violated field")
    void rejectsMissingRequiredFields() {
        given().spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(Map.of())
                .post("/accounts")
                .then()
                .statusCode(400)
                .body("messages", hasSize(greaterThanOrEqualTo(3)));
    }

    @Test
    @Story("Validation")
    @Description("Currency not matching the 3-letter ISO 4217 pattern is rejected with 400")
    void rejectsMalformedCurrency() {
        given().spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(accountRequest("Acct-" + uniqueSuffix(), "US1", "ASSET"))
                .post("/accounts")
                .then()
                .statusCode(400)
                .body("messages", hasItem(containsStringIgnoringCase("currency")));
    }

    @Test
    @Story("Validation")
    @Description("Currency shorter than 3 letters is rejected with 400")
    void rejectsTooShortCurrency() {
        given().spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(accountRequest("Acct-" + uniqueSuffix(), "US", "ASSET"))
                .post("/accounts")
                .then()
                .statusCode(400);
    }

    @Test
    @Story("Validation")
    @Description("An accountType value outside the documented enum fails request-body parsing with 400 (Jackson enum deserialization failure, not a bean-validation field error)")
    void rejectsUnknownAccountTypeEnumValue() {
        // NOTE: deliberately NOT using validatedSpec() here - this exercises how the *body*
        // is rejected, and we assert the exact (undocumented-in-detail, but 400-per-spec)
        // shape observed on the live app.
        given().spec(ApiClient.spec())
                .contentType("application/json")
                .body(Map.of("name", "A", "currency", "USD", "accountType", "BOGUS"))
                .post("/accounts")
                .then()
                .statusCode(400)
                .body("error", equalTo("Malformed Request"));
    }

    @Test
    @Story("Edge case")
    @Description("Unicode characters (accents, emoji, CJK) in the account name round-trip correctly")
    void acceptsUnicodeAccountName() {
        String name = "Café ☕ 日本語 Ünïcödé " + uniqueSuffix();
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .body(accountRequest(name, "USD", "ASSET"))
                .post("/accounts");
        response.then().statusCode(201).body("name", equalTo(name));
    }

    @Test
    @Story("Edge case")
    @Description("Extra/unknown fields in the request body are ignored rather than rejected")
    void ignoresUnknownExtraFields() {
        given().spec(ApiClient.spec())
                .contentType("application/json")
                .body(Map.of("name", "A-" + uniqueSuffix(), "currency", "USD", "accountType", "ASSET",
                        "unexpectedField", "surprise"))
                .post("/accounts")
                .then()
                .statusCode(201);
    }

    @Test
    @Story("Edge case")
    @Description("Malformed JSON body is rejected with 400, not 500")
    void rejectsMalformedJson() {
        given().spec(ApiClient.spec())
                .contentType("application/json")
                .body("{not valid json")
                .post("/accounts")
                .then()
                .statusCode(400)
                .body("error", equalTo("Malformed Request"));
    }

    @Test
    @Story("Edge case")
    @Description("Wrong Content-Type (text/plain instead of application/json) returns 415 Unsupported Media "
            + "Type, matching the documented 415 ErrorResponse for this operation")
    void wrongContentTypeReturns415() {
        given().spec(ApiClient.validatedSpec())
                .contentType("text/plain")
                .body("{\"name\":\"A\",\"currency\":\"USD\",\"accountType\":\"ASSET\"}")
                .post("/accounts")
                .then()
                .statusCode(415);
    }
}
