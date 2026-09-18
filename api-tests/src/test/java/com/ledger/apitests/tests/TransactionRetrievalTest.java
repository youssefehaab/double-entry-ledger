package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Epic("Double-Entry Ledger API")
@Feature("GET /transactions/{id}")
class TransactionRetrievalTest {

    @Test
    @Story("Happy path")
    @Description("A created transaction can be fetched by id and matches the TransactionResponse schema, "
            + "including its nested entries")
    void fetchesCreatedTransactionWithEntries() {
        String a = createAccount();
        String b = createAccount();
        Response created = postTransaction(randomIdempotencyKey(), simpleTransfer(a, b, 42, "fetch me"));
        created.then().statusCode(201);
        String id = created.jsonPath().getString("id");

        given().spec(ApiClient.validatedSpec())
                .get("/transactions/{id}", id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("description", equalTo("fetch me"))
                .body("status", equalTo("POSTED"))
                .body("createdAt", notNullValue())
                .body("postedAt", notNullValue())
                .body("entries", hasSize(2))
                .body("entries[0]", hasKey("id"))
                .body("entries[0]", hasKey("transactionId"))
                .body("entries[0].transactionId", equalTo(id));
    }

    @Test
    @Story("Not found")
    @Description("GET /transactions/{id} for an unknown (but well-formed) UUID returns 404")
    void unknownTransactionIdReturns404() {
        given().spec(ApiClient.validatedSpec())
                .get("/transactions/{id}", UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("error", notNullValue());
    }

    @Test
    @Story("Edge case")
    @Description("A malformed (non-UUID) path segment for {id} returns 400, matching the documented 400 "
            + "ErrorResponse for this operation ({id} is declared format: uuid)")
    void malformedUuidPathReturns400() {
        given().spec(ApiClient.validatedSpec())
                .get("/transactions/{id}", "not-a-valid-uuid")
                .then()
                .statusCode(400);
    }
}
