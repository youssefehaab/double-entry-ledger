package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 companion to {@link ConcurrentTransferIntegrationTest}: proves the
 * same "no lost/duplicated entries, exact reconciliation" guarantee holds
 * under real concurrent contention once two things Phase 1/2 added are both
 * in play at the same time - cross-currency FX conversion inside {@code
 * TransactionWriter#createAndPersist} and the V9 base-currency-amount
 * balance trigger - rather than re-running the same single-currency shape
 * and assuming it generalizes.
 *
 * <h2>Setup</h2>
 * Four accounts, four distinct currencies: USD (the ledger's base currency -
 * see {@code ledger.fx.base-currency} - so its entries are always an
 * identity conversion), EUR, GBP, JPY. Concurrent transfers are fired across
 * four pair "shapes":
 * <ul>
 *   <li>USD &lt;-&gt; EUR (rate EUR-&gt;USD 1.08 from {@code V7__create_fx_rates_table.sql})</li>
 *   <li>USD &lt;-&gt; GBP (rate GBP-&gt;USD 1.27)</li>
 *   <li>USD &lt;-&gt; JPY (rate JPY-&gt;USD 0.0067)</li>
 *   <li>EUR &lt;-&gt; GBP - deliberately the one shape with <b>no</b> USD leg,
 *       so both entries independently go through {@code TransactionWriter}'s
 *       currency-&gt;base-currency conversion (EUR-&gt;USD and GBP-&gt;USD
 *       respectively; {@code TransactionWriter} always converts to the base
 *       currency, never directly between two non-base currencies, so the
 *       EUR-&gt;GBP/GBP-&gt;EUR rows also seeded in V7 are not exercised by
 *       this write path and are not needed here)</li>
 * </ul>
 * The USD account participates in 3 of the 4 shapes (75% of all requests),
 * and EUR/GBP each participate in 2 of the 4 - real, unavoidable lock
 * contention on the same account rows from multiple concurrently-racing
 * threads, not an accidentally-partitioned workload where no two concurrent
 * requests ever touch the same account.
 *
 * <h2>Why this proves something, not just "it ran"</h2>
 * <ul>
 *   <li><b>Real concurrency.</b> Every submitted transfer runs on its own
 *       dedicated thread from a pool sized to match, held at a two-stage
 *       {@link CountDownLatch} starting line until every thread has arrived,
 *       then released together - same mechanism as {@link
 *       ConcurrentTransferIntegrationTest}, no sleeps, no retry loops.</li>
 *   <li><b>Independent expected-value computation.</b> Every transfer's
 *       expected base-currency (USD) value is computed in this file, in the
 *       test's own bookkeeping ({@link TransferSpec#baseAmount()}), using
 *       plain {@link BigDecimal} arithmetic against rate constants copied
 *       by hand from {@code V7__create_fx_rates_table.sql} - {@code
 *       DbFxRateProvider}/{@code FxRateProvider} is never called, directly or
 *       indirectly, to build the expectation. Rounding (scale 4,
 *       {@link RoundingMode#HALF_UP}) mirrors the documented contract on
 *       {@code DbFxRateProvider} (necessary to hand-compute the expected
 *       converted value per the task at hand), but every amount pair below
 *       is deliberately chosen so the multiplication is exact and rounding
 *       never actually has anything to round - see {@link
 *       #buildCrossCurrencyPairSpecs} for the sanity assertion that proves
 *       this for the one shape (EUR&lt;-&gt;GBP) where it is least obvious.</li>
 *   <li><b>Actual values read from persisted state directly, not via any
 *       production summation code.</b> {@code GET /accounts/{id}/balance}
 *       sums raw {@code amount} (native currency, see {@code
 *       EntryRepository#sumSignedAmountsByAccountId}), not {@code
 *       base_currency_amount} - there is no production endpoint for a
 *       base-currency balance yet. {@link #actualBaseCurrencyNetBalance}
 *       therefore reads the actual, persisted {@code base_currency_amount}
 *       column straight off the {@code entries} table via raw JDBC, the same
 *       "bypass the application layer entirely" approach {@link
 *       MultiCurrencyBalanceTriggerIntegrationTest} already uses for the
 *       trigger itself - never through {@code EntryRepository} or any
 *       application-level summation.</li>
 *   <li><b>Every submission individually accounted for</b> and <b>every
 *       account's balance checked</b>, not just an aggregate that happens to
 *       add up - same discipline as {@link ConcurrentTransferIntegrationTest}.</li>
 * </ul>
 */
class ConcurrentMultiCurrencyTransferIntegrationTest extends AbstractIntegrationTest {

    /** Per pair-shape; 4 shapes * 40 = 160 total concurrent requests. */
    private static final int PAIR_COUNT = 40;

    // Copied by hand from V7__create_fx_rates_table.sql's seed data -
    // deliberately never read via FxRateRepository/DbFxRateProvider, so a
    // bug in the production lookup/conversion path cannot also corrupt this
    // test's expectation and accidentally cancel out.
    private static final BigDecimal EUR_TO_USD = new BigDecimal("1.08000000");
    private static final BigDecimal GBP_TO_USD = new BigDecimal("1.27000000");
    private static final BigDecimal JPY_TO_USD = new BigDecimal("0.00670000");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    private DataSource dataSource;

    private UUID usdAccountId;
    private UUID eurAccountId;
    private UUID gbpAccountId;
    private UUID jpyAccountId;

    @BeforeEach
    void createAccounts() {
        usdAccountId = accountRepository.saveAndFlush(new Account("FX Concurrent USD", "USD", AccountType.ASSET)).getId();
        eurAccountId = accountRepository.saveAndFlush(new Account("FX Concurrent EUR", "EUR", AccountType.ASSET)).getId();
        gbpAccountId = accountRepository.saveAndFlush(new Account("FX Concurrent GBP", "GBP", AccountType.ASSET)).getId();
        jpyAccountId = accountRepository.saveAndFlush(new Account("FX Concurrent JPY", "JPY", AccountType.ASSET)).getId();
    }

    /**
     * One transfer this test intends to submit. {@code fromAmount}/{@code
     * toAmount} are each expressed in their own leg's account currency (they
     * are NOT expected to be numerically equal - that is the whole point of
     * a cross-currency transfer); {@code baseAmount} is the common
     * USD-equivalent value both legs convert to, computed here and asserted
     * (at construction, for the EUR&lt;-&gt;GBP shape) to be identical for
     * both legs before the request is ever fired.
     */
    private record TransferSpec(
            int index, UUID from, UUID to, BigDecimal fromAmount, BigDecimal toAmount, BigDecimal baseAmount,
            String idempotencyKey) {

        String requestBody() {
            // Debit `to`, credit `from` - same convention as
            // ConcurrentTransferIntegrationTest.
            return """
                    {
                      "description": "Concurrent FX transfer #%d",
                      "entries": [
                        {"accountId": "%s", "amount": "%s", "direction": "DEBIT"},
                        {"accountId": "%s", "amount": "%s", "direction": "CREDIT"}
                      ]
                    }
                    """.formatted(index, to, toAmount, from, fromAmount);
        }
    }

    private record TaskOutcome(int index, int httpStatus, String responseBody) {
        boolean succeeded() {
            return httpStatus == 201;
        }
    }

    @Test
    void concurrentMultiCurrencyTransfers_reconcileExactlyInBaseCurrency() throws Exception {
        AtomicInteger globalIndex = new AtomicInteger();
        List<TransferSpec> specs = new ArrayList<>();
        specs.addAll(buildUsdPairSpecs(
                usdAccountId, eurAccountId, EUR_TO_USD, new BigDecimal("1.00"), PAIR_COUNT, "usd-eur", globalIndex));
        specs.addAll(buildUsdPairSpecs(
                usdAccountId, gbpAccountId, GBP_TO_USD, new BigDecimal("1.00"), PAIR_COUNT, "usd-gbp", globalIndex));
        specs.addAll(buildUsdPairSpecs(
                usdAccountId, jpyAccountId, JPY_TO_USD, new BigDecimal("100.00"), PAIR_COUNT, "usd-jpy", globalIndex));
        specs.addAll(buildCrossCurrencyPairSpecs(eurAccountId, gbpAccountId, PAIR_COUNT, globalIndex));

        int total = specs.size();
        assertThat(total).as("sanity check on this test's own setup").isEqualTo(PAIR_COUNT * 4);

        ExecutorService executor = Executors.newFixedThreadPool(total);
        try {
            CountDownLatch ready = new CountDownLatch(total);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<TaskOutcome>> futures = new ArrayList<>(total);
            for (TransferSpec spec : specs) {
                futures.add(executor.submit(() -> fireTransfer(spec, ready, go)));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("every thread must reach the starting line before any of them proceeds")
                    .isTrue();
            go.countDown();

            List<TaskOutcome> outcomes = new ArrayList<>(total);
            for (Future<TaskOutcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }

            // --- Every one of the submitted transfers is individually accounted for. ---
            assertThat(outcomes).hasSize(total);
            List<TaskOutcome> failures = outcomes.stream().filter(o -> !o.succeeded()).toList();
            assertThat(failures)
                    .as("every concurrently-submitted, individually-valid FX transfer must succeed with 201; "
                            + "any failures indicate a lost/rejected transaction: %s", failures)
                    .isEmpty();

            // --- No lost/duplicated entries: exactly one transaction and two entries per success. ---
            assertThat(transactionRepository.count())
                    .as("one transaction row per successfully-processed transfer, no duplicates, none lost")
                    .isEqualTo((long) total);
            assertThat(entryRepository.count())
                    .as("exactly 2 entries per successfully-processed transaction")
                    .isEqualTo(total * 2L);

            // --- Base-currency balances reconcile exactly against this test's own independent bookkeeping. ---
            Map<UUID, BigDecimal> expectedBaseNet = new HashMap<>();
            for (UUID accountId : List.of(usdAccountId, eurAccountId, gbpAccountId, jpyAccountId)) {
                expectedBaseNet.put(accountId, BigDecimal.ZERO);
            }
            for (TransferSpec spec : specs) {
                expectedBaseNet.merge(spec.to(), spec.baseAmount(), BigDecimal::add);
                expectedBaseNet.merge(spec.from(), spec.baseAmount().negate(), BigDecimal::add);
            }
            // Sanity check on the test's own arithmetic: every transfer only
            // moves value between two of these four accounts, so the net
            // change across all of them must sum to zero.
            BigDecimal sumAcrossAllAccounts = expectedBaseNet.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sumAcrossAllAccounts).isEqualByComparingTo(BigDecimal.ZERO);

            try (Connection conn = dataSource.getConnection()) {
                for (Map.Entry<UUID, BigDecimal> entry : expectedBaseNet.entrySet()) {
                    BigDecimal actual = actualBaseCurrencyNetBalance(conn, entry.getKey());
                    assertThat(actual)
                            .as("account %s base-currency (USD) net balance", entry.getKey())
                            .isEqualByComparingTo(entry.getValue());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Builds {@code count} transfer specs alternating direction between the
     * USD account and one foreign-currency account - the USD leg's amount is
     * always exactly the base-currency amount (identity conversion), so the
     * foreign leg's amount is chosen (a small integer multiple of {@code
     * unitForeignAmount}) such that {@code foreignAmount * rate}, rounded to
     * scale 4, is always an exact value (no actual rounding), matching
     * whatever {@code DbFxRateProvider} independently computes when the
     * request is actually processed.
     */
    private List<TransferSpec> buildUsdPairSpecs(
            UUID usdId, UUID foreignId, BigDecimal foreignToUsdRate, BigDecimal unitForeignAmount, int count,
            String pairLabel, AtomicInteger globalIndex) {
        List<TransferSpec> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int multiplier = 1 + (i % 13);
            BigDecimal foreignAmount = unitForeignAmount.multiply(BigDecimal.valueOf(multiplier));
            BigDecimal baseAmount = foreignAmount.multiply(foreignToUsdRate).setScale(4, RoundingMode.HALF_UP);

            int idx = globalIndex.getAndIncrement();
            UUID from;
            UUID to;
            BigDecimal fromAmount;
            BigDecimal toAmount;
            if (i % 2 == 0) {
                from = usdId;
                fromAmount = baseAmount;
                to = foreignId;
                toAmount = foreignAmount;
            } else {
                from = foreignId;
                fromAmount = foreignAmount;
                to = usdId;
                toAmount = baseAmount;
            }
            specs.add(new TransferSpec(idx, from, to, fromAmount, toAmount, baseAmount, pairLabel + "-" + i));
        }
        return specs;
    }

    /**
     * Builds {@code count} transfer specs alternating direction between the
     * EUR and GBP accounts - deliberately the one shape with no USD leg, so
     * both entries independently convert to base currency. Amounts are
     * chosen (127.00 * k EUR against 108.00 * k GBP) specifically because
     * {@code 127 * 1.08 == 108 * 1.27 == 137.16} exactly - i.e. both legs'
     * independent conversions to USD land on the identical value with no
     * rounding, which the assertion below double-checks eagerly (at spec
     *-build time, before any request is ever fired) rather than trusting
     * the arithmetic silently.
     */
    private List<TransferSpec> buildCrossCurrencyPairSpecs(
            UUID eurId, UUID gbpId, int count, AtomicInteger globalIndex) {
        List<TransferSpec> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int k = 1 + (i % 10);
            BigDecimal eurAmount = new BigDecimal("127.00").multiply(BigDecimal.valueOf(k));
            BigDecimal gbpAmount = new BigDecimal("108.00").multiply(BigDecimal.valueOf(k));
            BigDecimal baseFromEur = eurAmount.multiply(EUR_TO_USD).setScale(4, RoundingMode.HALF_UP);
            BigDecimal baseFromGbp = gbpAmount.multiply(GBP_TO_USD).setScale(4, RoundingMode.HALF_UP);
            assertThat(baseFromEur)
                    .as("EUR<->GBP spec #%d must independently convert to the identical base-currency amount "
                            + "on both legs, or the transaction this spec builds would be rejected as unbalanced "
                            + "by the V9 trigger before this test's concurrency assertions even get to run", i)
                    .isEqualByComparingTo(baseFromGbp);

            int idx = globalIndex.getAndIncrement();
            UUID from;
            UUID to;
            BigDecimal fromAmount;
            BigDecimal toAmount;
            if (i % 2 == 0) {
                from = eurId;
                fromAmount = eurAmount;
                to = gbpId;
                toAmount = gbpAmount;
            } else {
                from = gbpId;
                fromAmount = gbpAmount;
                to = eurId;
                toAmount = eurAmount;
            }
            specs.add(new TransferSpec(idx, from, to, fromAmount, toAmount, baseFromEur, "eur-gbp-" + i));
        }
        return specs;
    }

    /**
     * Reads the actual, persisted {@code base_currency_amount} column
     * straight off the {@code entries} table via raw JDBC - deliberately
     * bypassing {@code EntryRepository}/{@code AccountService} and any other
     * application-level summation code, since {@code GET
     * /accounts/{id}/balance} sums native {@code amount}, not {@code
     * base_currency_amount}, and there is no production endpoint yet for a
     * base-currency balance. Same sign convention as {@code
     * check_transaction_balance} (V9) and {@code
     * EntryRepository#sumSignedAmountsByAccountId}: DEBIT adds, CREDIT
     * subtracts.
     */
    private BigDecimal actualBaseCurrencyNetBalance(Connection conn, UUID accountId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COALESCE(SUM(
                    CASE WHEN direction = 'DEBIT' THEN base_currency_amount ELSE -base_currency_amount END
                ), 0) AS net
                FROM entries
                WHERE account_id = ?
                """)) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal("net");
            }
        }
    }

    private TaskOutcome fireTransfer(TransferSpec spec, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", spec.idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.requestBody()))
                .andReturn();
        return new TaskOutcome(spec.index(), result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }
}
