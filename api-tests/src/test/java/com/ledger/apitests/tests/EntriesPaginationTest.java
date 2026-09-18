package com.ledger.apitests.tests;

import com.ledger.apitests.support.ApiClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.ledger.apitests.support.TestData.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Double-Entry Ledger API")
@Feature("GET /accounts/{id}/entries")
class EntriesPaginationTest {

    private String account;
    private static final int ENTRY_COUNT = 25; // spans multiple pages at size=10

    @BeforeEach
    void setUp() {
        account = createAccount();
        String counterparty = createAccount();
        for (int i = 0; i < ENTRY_COUNT; i++) {
            postTransaction(randomIdempotencyKey(), simpleTransfer(account, counterparty, 1, "entry " + i))
                    .then().statusCode(201);
        }
        // Each transaction posts exactly 1 entry against `account` (the DEBIT leg).
    }

    @Test
    @Story("Pagination mechanics")
    @Description("page/size params are honored: requesting size=10 returns at most 10 entries and the "
            + "correct totalElements/totalPages metadata")
    void pageAndSizeParamsAreHonored() {
        Response page0 = given().spec(ApiClient.validatedSpec())
                .queryParam("page", 0).queryParam("size", 10)
                .get("/accounts/{id}/entries", account);

        page0.then().statusCode(200)
                .body("entries", hasSize(10))
                .body("page", equalTo(0))
                .body("size", equalTo(10))
                .body("totalElements", equalTo(ENTRY_COUNT))
                .body("totalPages", equalTo(3)); // ceil(25/10) = 3
    }

    @Test
    @Story("Pagination mechanics")
    @Description("Walking every page with size=10 yields all entries exactly once (no duplicates, none skipped) "
            + "in the documented createdAt-then-id stable order")
    void walkingAllPagesYieldsEveryEntryExactlyOnce() {
        Set<String> seenIds = new HashSet<>();
        int totalPages = given().spec(ApiClient.spec())
                .queryParam("page", 0).queryParam("size", 10)
                .get("/accounts/{id}/entries", account)
                .jsonPath().getInt("totalPages");

        for (int page = 0; page < totalPages; page++) {
            List<String> ids = given().spec(ApiClient.spec())
                    .queryParam("page", page).queryParam("size", 10)
                    .get("/accounts/{id}/entries", account)
                    .then().statusCode(200)
                    .extract().jsonPath().getList("entries.id", String.class);
            for (String id : ids) {
                assertThat("entry id must not appear on more than one page: " + id, seenIds.add(id), is(true));
            }
        }
        assertThat(seenIds, hasSize(ENTRY_COUNT));
    }

    @Test
    @Story("Pagination mechanics")
    @Description("Default pagination (page/size omitted) returns a sensible default page (spring-data default size=20)")
    void defaultPaginationWhenParamsOmitted() {
        given().spec(ApiClient.spec())
                .get("/accounts/{id}/entries", account)
                .then()
                .statusCode(200)
                .body("page", equalTo(0))
                .body("size", equalTo(20))
                .body("entries", hasSize(20))
                .body("totalElements", equalTo(ENTRY_COUNT));
    }

    @Test
    @Story("Pagination mechanics")
    @Description("Requesting a page beyond the last page returns 200 with an empty entries array, not an error")
    void pageBeyondLastPageReturnsEmptyPageNotError() {
        given().spec(ApiClient.validatedSpec())
                .queryParam("page", 999).queryParam("size", 10)
                .get("/accounts/{id}/entries", account)
                .then()
                .statusCode(200)
                .body("entries", empty())
                .body("totalElements", equalTo(ENTRY_COUNT));
    }

    @Test
    @Story("Ordering")
    @Description("Entries are returned ordered by createdAt (ascending), matching the documented stable sort")
    void entriesAreOrderedByCreatedAtAscending() {
        List<String> createdAtValues = given().spec(ApiClient.spec())
                .queryParam("page", 0).queryParam("size", ENTRY_COUNT)
                .get("/accounts/{id}/entries", account)
                .then().statusCode(200)
                .extract().jsonPath().getList("entries.createdAt", String.class);

        List<String> sorted = createdAtValues.stream().sorted().toList();
        assertThat(createdAtValues, equalTo(sorted));
    }

    @Test
    @Story("Edge case")
    @Description("size=1 returns exactly 1 entry per page with correct totalPages = totalElements")
    void sizeOneReturnsOneEntryPerPage() {
        given().spec(ApiClient.spec())
                .queryParam("page", 0).queryParam("size", 1)
                .get("/accounts/{id}/entries", account)
                .then()
                .statusCode(200)
                .body("entries", hasSize(1))
                .body("totalPages", equalTo(ENTRY_COUNT));
    }

    @Test
    @Story("Edge case")
    @Description("A negative page index does not error; Spring's Pageable resolver clamps it to page 0 "
            + "(observed/documented behavior - the spec itself doesn't state what happens for an out-of-range "
            + "page, this test pins down the actual behavior)")
    void negativePageIsClampedNotRejected() {
        given().spec(ApiClient.spec())
                .queryParam("page", -1).queryParam("size", 10)
                .get("/accounts/{id}/entries", account)
                .then()
                .statusCode(200)
                .body("page", equalTo(0));
    }

    @Test
    @Story("Not found")
    @Description("GET /accounts/{id}/entries for an unknown account id returns 404")
    void unknownAccountReturns404() {
        given().spec(ApiClient.validatedSpec())
                .queryParam("page", 0).queryParam("size", 10)
                .get("/accounts/{id}/entries", UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("status", equalTo(404));
    }

    @Test
    @Story("Contract")
    @Description("PagedEntriesResponse body matches the documented schema: entries, page, size, totalElements, totalPages")
    void pagedResponseMatchesSchema() {
        given().spec(ApiClient.validatedSpec())
                .queryParam("page", 0).queryParam("size", 5)
                .get("/accounts/{id}/entries", account)
                .then()
                .statusCode(200)
                .body("$", hasKey("entries"))
                .body("$", hasKey("page"))
                .body("$", hasKey("size"))
                .body("$", hasKey("totalElements"))
                .body("$", hasKey("totalPages"))
                .body("entries[0]", hasKey("id"))
                .body("entries[0]", hasKey("transactionId"))
                .body("entries[0]", hasKey("accountId"))
                .body("entries[0]", hasKey("amount"))
                .body("entries[0]", hasKey("direction"))
                .body("entries[0]", hasKey("createdAt"));
    }
}
