package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the V9 balance trigger ({@code check_transaction_balance}, as
 * replaced by {@code V9__enforce_transaction_balance_base_currency.sql})
 * checks {@code sum(base_currency_amount)} signed by direction - not {@code
 * sum(amount)} the way the original V5 trigger did - at the raw SQL/JDBC
 * level, bypassing the application entirely, precisely to prove the
 * guarantee is enforced by Postgres itself.
 *
 * <p>Complements {@link LedgerSchemaConstraintsIntegrationTest} (which
 * already covers the single-currency/identity case with FX columns
 * populated) with the genuinely multi-currency and missing-FX-data cases.
 */
class MultiCurrencyBalanceTriggerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void entriesWithDifferentNativeAmountsButBalancedBaseCurrencyAmountsCommitSuccessfully() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID eurAccountId = insertAccount(conn, "EUR Cash", "EUR", "ASSET");
            UUID usdAccountId = insertAccount(conn, "USD Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "multi-ccy-balanced-key-1");

            // Native amounts differ (100.00 vs 108.00 - not equal as raw
            // numbers) but base_currency_amount is equal on both legs
            // (108.0000 each) - this is exactly a balanced cross-currency
            // transaction at a 1.08 EUR->USD rate. The old V5 trigger
            // (sum(amount)) would have rejected this; V9 must accept it.
            insertEntry(conn, transactionId, eurAccountId, new BigDecimal("100.00"), "DEBIT",
                    new BigDecimal("108.0000"), new BigDecimal("1.08000000"));
            insertEntry(conn, transactionId, usdAccountId, new BigDecimal("108.00"), "CREDIT",
                    new BigDecimal("108.0000"), BigDecimal.ONE);

            // Must not throw.
            conn.commit();

            assertThat(countEntries(conn, transactionId)).isEqualTo(2);
        }
    }

    @Test
    void balancedNativeAmountsButUnbalancedBaseCurrencyAmountsAreRejectedAtCommit() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "multi-ccy-unbalanced-key-1");

            // Native amounts are equal (100.00 == 100.00) but
            // base_currency_amount deliberately is not (100.0000 vs
            // 99.0000, as if a buggy/inconsistent conversion had been
            // applied) - V9 must reject this even though a native-amount-only
            // check (the old V5 behavior) would have accepted it.
            insertEntry(conn, transactionId, cashAccountId, new BigDecimal("100.00"), "DEBIT",
                    new BigDecimal("100.0000"), BigDecimal.ONE);
            insertEntry(conn, transactionId, revenueAccountId, new BigDecimal("100.00"), "CREDIT",
                    new BigDecimal("99.0000"), BigDecimal.ONE);

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not balanced");

            conn.rollback();
        }
    }

    @Test
    void missingBaseCurrencyAmountIsRejectedAtCommitEvenIfNativeAmountsBalance() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "multi-ccy-missing-fx-key-1");

            // Balanced native amounts (100.00 == 100.00), but the FX columns
            // are left entirely NULL - as if a write bypassed TransactionWriter
            // and never populated them (V8's CHECK constraint permits "all
            // three NULL together"). V9 must treat this as a hard failure,
            // not silently pass it (see the V9 migration comment on why a
            // naive SUM(...)-over-NULL version would wrongly do exactly that).
            insertEntryWithoutFxColumns(conn, transactionId, cashAccountId, new BigDecimal("100.00"), "DEBIT");
            insertEntryWithoutFxColumns(conn, transactionId, revenueAccountId, new BigDecimal("100.00"), "CREDIT");

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("missing base_currency_amount");

            conn.rollback();
        }
    }

    private UUID insertAccount(Connection conn, String name, String currency, String accountType)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (name, currency, account_type) VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, currency);
            ps.setString(3, accountType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertTransaction(Connection conn, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions (idempotency_key, description) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, "multi-currency schema constraint test");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertEntry(
            Connection conn, UUID transactionId, UUID accountId, BigDecimal amount, String direction,
            BigDecimal baseCurrencyAmount, BigDecimal fxRateUsed)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entries "
                        + "(transaction_id, account_id, amount, direction, base_currency_amount, "
                        + "fx_rate_used, fx_rate_effective_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, direction);
            ps.setBigDecimal(5, baseCurrencyAmount);
            ps.setBigDecimal(6, fxRateUsed);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertEntryWithoutFxColumns(
            Connection conn, UUID transactionId, UUID accountId, BigDecimal amount, String direction)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entries (transaction_id, account_id, amount, direction) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, direction);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private int countEntries(Connection conn, UUID transactionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM entries WHERE transaction_id = ?")) {
            ps.setObject(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
