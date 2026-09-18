package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;

import static com.ledger.apitests.support.TestData.createAccount;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@Epic("Double-Entry Ledger API")
@Feature("Cross-cutting edge cases")
class EdgeCaseAndContentNegotiationTest {

    @Test
    @Story("Malformed path parameters")
    @Description("A malformed (non-UUID) {id} on GET /accounts/{id}/balance returns 400, matching the "
            + "documented 400 ErrorResponse for this operation")
    void malformedUuidOnBalanceReturns400() {
        given().spec(ApiClient.validatedSpec())
                .get("/accounts/{id}/balance", "not-a-uuid")
                .then()
                .statusCode(400);
    }

    @Test
    @Story("Malformed path parameters")
    @Description("A malformed (non-UUID) {id} on GET /accounts/{id}/entries returns 400, matching the "
            + "documented 400 ErrorResponse for this operation")
    void malformedUuidOnEntriesReturns400() {
        given().spec(ApiClient.validatedSpec())
                .queryParam("page", 0).queryParam("size", 10)
                .get("/accounts/{id}/entries", "not-a-uuid")
                .then()
                .statusCode(400);
    }

    @Test
    @Story("Content negotiation")
    @Description("Requesting an unsupported Accept type (application/xml) on a JSON-only endpoint returns "
            + "406 Not Acceptable (standard Spring behavior; not explicitly documented in the spec but not a "
            + "concerning deviation)")
    void unsupportedAcceptHeaderReturns406() {
        String account = createAccount();
        given().spec(ApiClient.spec())
                .accept("application/xml")
                .get("/accounts/{id}/balance", account)
                .then()
                .statusCode(406);
    }

    @Test
    @Story("Content negotiation")
    @Description("Endpoints respond with Content-Type: application/json even though the spec declares '*/*' "
            + "for response content")
    void responsesAreActuallyJson() {
        String account = createAccount();
        given().spec(ApiClient.spec())
                .get("/accounts/{id}/balance", account)
                .then()
                .statusCode(200)
                .contentType("application/json");
    }
}
