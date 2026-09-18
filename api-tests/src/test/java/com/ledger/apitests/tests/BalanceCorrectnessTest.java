package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@Epic("Double-Entry Ledger API")
@Feature("GET /accounts/{id}/balance")
class BalanceCorrectnessTest {

    @Test
    @Story("Fresh account")
    @Description("A freshly created account with no transactions has balance 0")
    void freshAccountHasZeroBalance() {
        String account = createAccount();
        BigDecimal balance = getBalance(account);
        assertThat(balance.compareTo(BigDecimal.ZERO), equalTo(0));
    }

    @Test
    @Story("Debit-positive sign convention")
    @Description("Balance reflects the correct derived value across a sequence of balanced transactions: "
            + "DEBIT adds to the account's balance, CREDIT subtracts, independent of account_type "
            + "(verified after each step, per spec's stated 'debit-positive' convention)")
    void balanceReflectsSequenceOfTransactionsStepByStep() {
        String accountA = createAccount(); // will be net DEBITed (balance goes up)
        String accountB = createAccount(); // will be net CREDITed (balance goes down)

        assertThat(getBalance(accountA).compareTo(BigDecimal.ZERO), equalTo(0));
        assertThat(getBalance(accountB).compareTo(BigDecimal.ZERO), equalTo(0));

        postTransaction(randomIdempotencyKey(), simpleTransfer(accountA, accountB, 100, "step 1")).then().statusCode(201);
        assertThat("after DEBIT 100, A's balance is 100", getBalance(accountA).compareTo(new BigDecimal("100")), equalTo(0));
        assertThat("after CREDIT 100, B's balance is -100", getBalance(accountB).compareTo(new BigDecimal("-100")), equalTo(0));

        postTransaction(randomIdempotencyKey(), simpleTransfer(accountA, accountB, 25.5, "step 2")).then().statusCode(201);
        assertThat(getBalance(accountA).compareTo(new BigDecimal("125.5")), equalTo(0));
        assertThat(getBalance(accountB).compareTo(new BigDecimal("-125.5")), equalTo(0));

        // Now reverse direction: B pays A back 50.
        postTransaction(randomIdempotencyKey(), simpleTransfer(accountB, accountA, 50, "step 3 - reversal")).then().statusCode(201);
        assertThat(getBalance(accountA).compareTo(new BigDecimal("75.5")), equalTo(0));
        assertThat(getBalance(accountB).compareTo(new BigDecimal("-75.5")), equalTo(0));
    }

    @Test
    @Story("Multi-account fan-in")
    @Description("A single transaction with multiple entries on the same account correctly accumulates into that account's balance")
    void multipleEntriesOnSameAccountAccumulate() {
        String hub = createAccount();
        String spoke1 = createAccount();
        String spoke2 = createAccount();

        // hub receives (CREDIT, i.e. balance decreases) from two spokes in one transaction; to balance,
        // hub must be DEBITed the sum. Use a 3-legged transaction: DEBIT hub 30, CREDIT spoke1 10, CREDIT spoke2 20.
        postTransaction(randomIdempotencyKey(), transactionRequest("fan-in", java.util.List.of(
                entry(hub, 30, "DEBIT"),
                entry(spoke1, 10, "CREDIT"),
                entry(spoke2, 20, "CREDIT")
        ))).then().statusCode(201);

        assertThat(getBalance(hub).compareTo(new BigDecimal("30")), equalTo(0));
        assertThat(getBalance(spoke1).compareTo(new BigDecimal("-10")), equalTo(0));
        assertThat(getBalance(spoke2).compareTo(new BigDecimal("-20")), equalTo(0));
    }

    @Test
    @Story("Not found")
    @Description("GET /accounts/{id}/balance for an unknown account id returns 404")
    void unknownAccountReturns404() {
        given().spec(ApiClient.validatedSpec())
                .get("/accounts/{id}/balance", UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("status", equalTo(404));
    }

    @Test
    @Story("Contract")
    @Description("BalanceResponse body matches the documented schema: accountId, balance, asOf")
    void balanceResponseMatchesSchema() {
        String account = createAccount();
        given().spec(ApiClient.validatedSpec())
                .get("/accounts/{id}/balance", account)
                .then()
                .statusCode(200)
                .body("accountId", equalTo(account))
                .body("balance", org.hamcrest.Matchers.notNullValue())
                .body("asOf", org.hamcrest.Matchers.notNullValue());
    }

    private static BigDecimal getBalance(String accountId) {
        String raw = given().spec(ApiClient.spec())
                .get("/accounts/{id}/balance", accountId)
                .then().statusCode(200)
                .extract().jsonPath().getString("balance");
        return new BigDecimal(raw);
    }
}
