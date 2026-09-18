package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Black-box coverage for v1.1's multi-currency/FX conversion behavior on {@code POST /transactions}
 * (see {@code EntryResponse.baseCurrencyAmount}/{@code fxRateUsed}/{@code fxRateEffectiveAt} in
 * openapi.yaml 0.3.0).
 *
 * <p>Every cross-currency test below is deliberately constructed so the two legs' native amounts
 * convert to EXACTLY equal base-currency amounts at NUMERIC(19,4) scale (no rounding ambiguity) -
 * this mirrors the DB-level balance trigger's own exact-equality check (V9, no epsilon tolerance),
 * and lets each assertion cross-check the response against a hand-computed value derived directly
 * from the real seeded rates in
 * {@code src/main/resources/db/migration/V7__create_fx_rates_table.sql}, not just "field is
 * present":
 * <ul>
 *   <li>EUR-&gt;USD = 1.08000000</li>
 *   <li>GBP-&gt;USD = 1.27000000</li>
 *   <li>JPY-&gt;USD = 0.00670000</li>
 *   <li>EUR-&gt;GBP = 0.85039370 (not used directly here - see the EUR/GBP test, which instead
 *       cross-checks via each leg's own EUR-&gt;USD / GBP-&gt;USD rate, since base currency is USD,
 *       not GBP)</li>
 * </ul>
 *
 * <p>All entries[] index assumptions below (entries[0] = the first entry object in the request,
 * entries[1] = the second) follow the same convention already relied on elsewhere in this suite
 * (see {@code TransactionIdempotencyTest#identicalReplayReturns200WithSameTransactionNoDuplicate}'s
 * use of {@code entries[0].amount}) - each test still self-checks the assumed accountId at that
 * index before trusting the rest of that entry's fields, so a response-ordering change fails loudly
 * with a clear message rather than silently comparing the wrong entry.
 */
@Epic("Double-Entry Ledger API")
@Feature("POST /transactions - multi-currency / FX conversion (v1.1)")
class FxConversionApiTest {

    @Test
    @Story("Same-currency identity conversion")
    @Description("A USD-to-USD transaction (base currency == account currency on both legs) has "
            + "baseCurrencyAmount == amount and fxRateUsed == 1 on every entry, proven over the real HTTP API "
            + "(not just trusted from the main app's own test suite)")
    void sameCurrencyTransactionHasIdentityConversion() {
        String usdAccountA = createAccount("USD A " + uniqueSuffix(), "USD", "ASSET");
        String usdAccountB = createAccount("USD B " + uniqueSuffix(), "USD", "ASSET");

        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(simpleTransfer(usdAccountA, usdAccountB, new BigDecimal("42.50"), "same-currency identity"))
                .post("/transactions");

        response.then().statusCode(201).body("entries", hasSize(2));

        for (int i = 0; i < 2; i++) {
            BigDecimal amount = bigDecimalAt(response, i, "amount");
            BigDecimal baseCurrencyAmount = bigDecimalAt(response, i, "baseCurrencyAmount");
            BigDecimal fxRateUsed = bigDecimalAt(response, i, "fxRateUsed");

            assertThat("entries[" + i + "].baseCurrencyAmount must equal amount exactly for a same-currency entry",
                    baseCurrencyAmount.compareTo(amount), equalTo(0));
            assertThat("entries[" + i + "].fxRateUsed must be exactly 1 (identity) for a same-currency entry",
                    fxRateUsed.compareTo(BigDecimal.ONE), equalTo(0));
            assertThat("entries[" + i + "].fxRateEffectiveAt must still be populated even for an identity conversion",
                    response.jsonPath().getString("entries[" + i + "].fxRateEffectiveAt"), notNullValue());
        }
    }

    @Test
    @Story("Cross-currency conversion")
    @Description("A balanced USD/EUR transaction (108.00 USD debit vs 100.00 EUR credit) posts 201, and the "
            + "EUR entry's baseCurrencyAmount/fxRateUsed match the exact seeded EUR->USD rate of 1.08000000 "
            + "from V7__create_fx_rates_table.sql; the USD entry's conversion is the identity (rate 1)")
    void usdEurCrossCurrencyTransactionUsesSeededRate() {
        String usdAccount = createAccount("USD " + uniqueSuffix(), "USD", "ASSET");
        String eurAccount = createAccount("EUR " + uniqueSuffix(), "EUR", "ASSET");

        // 100.00 EUR * seeded rate 1.08 = 108.0000 USD exactly - no rounding ambiguity, so the
        // transaction is genuinely balanced in base currency (USD) and the DB's exact-equality
        // balance trigger (V9) accepts it.
        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("USD/EUR cross-currency transfer", List.of(
                        entry(usdAccount, new BigDecimal("108.00"), "DEBIT"),
                        entry(eurAccount, new BigDecimal("100.00"), "CREDIT")
                )))
                .post("/transactions");

        response.then().statusCode(201).body("entries", hasSize(2));

        assertAccountAtIndex(response, 0, usdAccount);
        assertAccountAtIndex(response, 1, eurAccount);

        assertThat("USD leg's baseCurrencyAmount must equal its own amount (identity conversion)",
                bigDecimalAt(response, 0, "baseCurrencyAmount").compareTo(new BigDecimal("108.00")), equalTo(0));
        assertThat("USD leg's fxRateUsed must be exactly 1",
                bigDecimalAt(response, 0, "fxRateUsed").compareTo(BigDecimal.ONE), equalTo(0));

        assertThat("EUR leg's baseCurrencyAmount must be 100.00 EUR converted at the seeded 1.08 rate = 108.0000 USD",
                bigDecimalAt(response, 1, "baseCurrencyAmount").compareTo(new BigDecimal("108.0000")), equalTo(0));
        assertThat("EUR leg's fxRateUsed must be exactly the seeded EUR->USD rate, 1.08",
                bigDecimalAt(response, 1, "fxRateUsed").compareTo(new BigDecimal("1.08")), equalTo(0));
        assertThat(response.jsonPath().getString("entries[1].fxRateEffectiveAt"), notNullValue());
    }

    @Test
    @Story("Cross-currency conversion")
    @Description("A balanced USD/GBP transaction (161.29 USD debit vs 127.00 GBP credit) cross-checks against "
            + "the exact seeded GBP->USD rate of 1.27000000 (127.00 * 1.27 = 161.2900 exactly)")
    void usdGbpCrossCurrencyTransactionUsesSeededRate() {
        String usdAccount = createAccount("USD " + uniqueSuffix(), "USD", "ASSET");
        String gbpAccount = createAccount("GBP " + uniqueSuffix(), "GBP", "ASSET");

        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("USD/GBP cross-currency transfer", List.of(
                        entry(usdAccount, new BigDecimal("161.29"), "DEBIT"),
                        entry(gbpAccount, new BigDecimal("127.00"), "CREDIT")
                )))
                .post("/transactions");

        response.then().statusCode(201).body("entries", hasSize(2));

        assertAccountAtIndex(response, 0, usdAccount);
        assertAccountAtIndex(response, 1, gbpAccount);

        assertThat("GBP leg's baseCurrencyAmount must be 127.00 GBP converted at the seeded 1.27 rate = 161.2900 USD",
                bigDecimalAt(response, 1, "baseCurrencyAmount").compareTo(new BigDecimal("161.2900")), equalTo(0));
        assertThat("GBP leg's fxRateUsed must be exactly the seeded GBP->USD rate, 1.27",
                bigDecimalAt(response, 1, "fxRateUsed").compareTo(new BigDecimal("1.27")), equalTo(0));
    }

    @Test
    @Story("Cross-currency conversion")
    @Description("A balanced USD/JPY transaction (67.00 USD debit vs 10000 JPY credit) cross-checks against "
            + "the exact seeded JPY->USD rate of 0.00670000 (10000 * 0.0067 = 67.0000 exactly) - JPY chosen "
            + "deliberately as the seeded pair with the most extreme magnitude difference")
    void usdJpyCrossCurrencyTransactionUsesSeededRate() {
        String usdAccount = createAccount("USD " + uniqueSuffix(), "USD", "ASSET");
        String jpyAccount = createAccount("JPY " + uniqueSuffix(), "JPY", "ASSET");

        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("USD/JPY cross-currency transfer", List.of(
                        entry(usdAccount, new BigDecimal("67.00"), "DEBIT"),
                        entry(jpyAccount, new BigDecimal("10000"), "CREDIT")
                )))
                .post("/transactions");

        response.then().statusCode(201).body("entries", hasSize(2));

        assertAccountAtIndex(response, 0, usdAccount);
        assertAccountAtIndex(response, 1, jpyAccount);

        assertThat("JPY leg's baseCurrencyAmount must be 10000 JPY converted at the seeded 0.0067 rate = 67.0000 USD",
                bigDecimalAt(response, 1, "baseCurrencyAmount").compareTo(new BigDecimal("67.0000")), equalTo(0));
        assertThat("JPY leg's fxRateUsed must be exactly the seeded JPY->USD rate, 0.0067",
                bigDecimalAt(response, 1, "fxRateUsed").compareTo(new BigDecimal("0.0067")), equalTo(0));
    }

    @Test
    @Story("Cross-currency conversion")
    @Description("Both legs in non-base currencies (EUR debit / GBP credit, base currency is USD) still "
            + "converts and balances correctly: each leg is converted independently to USD via its OWN "
            + "seeded rate (EUR->USD 1.08, GBP->USD 1.27), not via any direct EUR<->GBP rate - "
            + "127.00 EUR * 1.08 = 137.1600 USD == 108.00 GBP * 1.27 = 137.1600 USD")
    void neitherLegInBaseCurrencyStillConvertsPerLegIndependently() {
        String eurAccount = createAccount("EUR " + uniqueSuffix(), "EUR", "ASSET");
        String gbpAccount = createAccount("GBP " + uniqueSuffix(), "GBP", "ASSET");

        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(transactionRequest("EUR/GBP transfer, neither leg in base currency (USD)", List.of(
                        entry(eurAccount, new BigDecimal("127.00"), "DEBIT"),
                        entry(gbpAccount, new BigDecimal("108.00"), "CREDIT")
                )))
                .post("/transactions");

        response.then().statusCode(201).body("entries", hasSize(2));

        assertAccountAtIndex(response, 0, eurAccount);
        assertAccountAtIndex(response, 1, gbpAccount);

        assertThat("EUR leg must convert via its own EUR->USD rate (1.08): 127.00 * 1.08 = 137.1600",
                bigDecimalAt(response, 0, "baseCurrencyAmount").compareTo(new BigDecimal("137.1600")), equalTo(0));
        assertThat(bigDecimalAt(response, 0, "fxRateUsed").compareTo(new BigDecimal("1.08")), equalTo(0));

        assertThat("GBP leg must convert via its own GBP->USD rate (1.27): 108.00 * 1.27 = 137.1600",
                bigDecimalAt(response, 1, "baseCurrencyAmount").compareTo(new BigDecimal("137.1600")), equalTo(0));
        assertThat(bigDecimalAt(response, 1, "fxRateUsed").compareTo(new BigDecimal("1.27")), equalTo(0));
    }

    @Test
    @Story("Unsupported currency pair")
    @Description("An entry on an account in a currency with NO seeded FX rate at all (CHF - not seeded for "
            + "any pair in V7) returns 422 with an ErrorResponse-shaped body naming the unsupported pair, "
            + "matching the same 422 pattern as TransactionBalanceValidationTest.unbalancedEntriesReturn422")
    void unseededCurrencyPairReturns422WithErrorResponseShape() {
        String chfAccount = createAccount("CHF " + uniqueSuffix(), "CHF", "ASSET");
        String usdAccount = createAccount("USD " + uniqueSuffix(), "USD", "ASSET");

        Response response = given()
                .spec(ApiClient.validatedSpec())
                .contentType("application/json")
                .header("Idempotency-Key", randomIdempotencyKey())
                .body(simpleTransfer(chfAccount, usdAccount, new BigDecimal("50.00"), "unsupported CHF pair"))
                .post("/transactions");

        response.then()
                .statusCode(422)
                .body("status", equalTo(422))
                .body("error", notNullValue())
                .body("messages", not(empty()))
                .body("messages", hasItem(containsString("CHF")));
    }

    @Test
    @Story("Unsupported currency pair")
    @Description("A 422 from an unsupported-currency-pair attempt does not consume/persist its Idempotency-Key "
            + "as if it were a successful transaction: replaying the SAME key with a DIFFERENT, valid "
            + "(same-currency) body afterward succeeds with 201 - not a 200 replay (which would mean the 422 "
            + "attempt was wrongly treated as having created a transaction) and not a 409 conflict (which "
            + "would mean the key was wrongly treated as already bound to the failed body). This is the "
            + "black-box proof (this suite has no DB access) that no transaction row was created by the "
            + "422 attempt.")
    void failedUnsupportedCurrencyAttemptDoesNotConsumeIdempotencyKey() {
        String chfAccount = createAccount("CHF " + uniqueSuffix(), "CHF", "ASSET");
        String usdAccountA = createAccount("USD A " + uniqueSuffix(), "USD", "ASSET");
        String usdAccountB = createAccount("USD B " + uniqueSuffix(), "USD", "ASSET");
        String key = randomIdempotencyKey();

        Response failed = postTransaction(key, simpleTransfer(chfAccount, usdAccountA, new BigDecimal("10.00"), "will fail"));
        failed.then().statusCode(422);

        Response retryWithValidBody = postTransaction(
                key, simpleTransfer(usdAccountA, usdAccountB, new BigDecimal("10.00"), "genuinely new attempt, same key"));
        retryWithValidBody.then().statusCode(201);

        assertThat("the retried transaction must be a real, freshly-created transaction, not a replayed/ghost one",
                retryWithValidBody.jsonPath().getString("id"), notNullValue());
        assertThat(retryWithValidBody.jsonPath().getString("description"), equalTo("genuinely new attempt, same key"));
    }

    private static void assertAccountAtIndex(Response response, int index, String expectedAccountId) {
        assertThat("entries[" + index + "].accountId did not match the expected account - response ordering "
                        + "assumption (request order == response order) may have changed; update this test's "
                        + "index assumptions rather than trusting the wrong entry's fields",
                response.jsonPath().getString("entries[" + index + "].accountId"), equalTo(expectedAccountId));
    }

    private static BigDecimal bigDecimalAt(Response response, int index, String field) {
        String raw = response.jsonPath().getString("entries[" + index + "]." + field);
        assertThat("entries[" + index + "]." + field + " must be present in the response", raw, notNullValue());
        return new BigDecimal(raw);
    }
}
