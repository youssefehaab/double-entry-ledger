package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the two DB-level invariants required by this phase hold even when
 * bypassing the application entirely and talking raw SQL/JDBC directly to
 * Postgres:
 *
 * <ol>
 *   <li>A set of entries for a transaction that does not balance in the
 *       base currency (sum of signed base_currency_amount != 0, as of the
 *       V9 migration) can never be committed.</li>
 *   <li>An entry row, once inserted, can never be UPDATEd or DELETEd.</li>
 * </ol>
 *
 * <p>These are exercised with plain JDBC (autoCommit(false) + explicit
 * commit()) rather than through JPA/Spring's transaction manager, precisely
 * to prove the guarantee is enforced by Postgres itself and not merely by
 * application-level code paths.
 *
 * <p>{@link #insertEntry} always populates base_currency_amount /
 * fx_rate_used / fx_rate_effective_at alongside amount (all single-currency
 * USD accounts here, so base_currency_amount == amount and fx_rate_used ==
 * 1 - the identity case) precisely so these raw-SQL tests keep exercising
 * the balance check itself (V9) rather than tripping the separate "missing
 * base_currency_amount" failure the V9 trigger also raises - see {@link
 * MultiCurrencyBalanceTriggerIntegrationTest} for tests of that failure
 * mode and of genuine multi-currency (non-1:1) balancing.
 */
class LedgerSchemaConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void balancedEntriesCommitSuccessfully() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "balanced-tx-key-1");

            insertEntry(conn, transactionId, cashAccountId, new BigDecimal("100.00"), "DEBIT");
            insertEntry(conn, transactionId, revenueAccountId, new BigDecimal("100.00"), "CREDIT");

            // Must not throw: debit total == credit total for this transaction_id.
            conn.commit();

            assertThat(countEntries(conn, transactionId)).isEqualTo(2);
        }
    }

    @Test
    void unbalancedEntriesAreRejectedAtCommit() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "unbalanced-tx-key-1");

            // Debit 100, credit only 40: unbalanced.
            insertEntry(conn, transactionId, cashAccountId, new BigDecimal("100.00"), "DEBIT");
            insertEntry(conn, transactionId, revenueAccountId, new BigDecimal("40.00"), "CREDIT");

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not balanced");

            conn.rollback();
        }
    }

    @Test
    void singleEntryWithNoOffsettingEntryIsRejectedAtCommit() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID transactionId = insertTransaction(conn, "single-entry-tx-key-1");

            // A lone debit with nothing offsetting it can never be balanced.
            insertEntry(conn, transactionId, cashAccountId, new BigDecimal("25.00"), "DEBIT");

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("not balanced");

            conn.rollback();
        }
    }

    @Test
    void entriesCannotBeUpdatedOnceCommitted() throws Exception {
        UUID entryId;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "immutable-update-tx-key-1");
            entryId = insertEntry(conn, transactionId, cashAccountId, new BigDecimal("10.00"), "DEBIT");
            insertEntry(conn, transactionId, revenueAccountId, new BigDecimal("10.00"), "CREDIT");
            conn.commit();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE entries SET amount = 999.00 WHERE id = ?")) {
                ps.setObject(1, entryId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void entriesCannotBeDeletedOnceCommitted() throws Exception {
        UUID entryId;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID revenueAccountId = insertAccount(conn, "Revenue", "USD", "EQUITY");
            UUID transactionId = insertTransaction(conn, "immutable-delete-tx-key-1");
            entryId = insertEntry(conn, transactionId, cashAccountId, new BigDecimal("10.00"), "DEBIT");
            insertEntry(conn, transactionId, revenueAccountId, new BigDecimal("10.00"), "CREDIT");
            conn.commit();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM entries WHERE id = ?")) {
                ps.setObject(1, entryId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void nonPositiveAmountIsRejectedByCheckConstraint() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            UUID cashAccountId = insertAccount(conn, "Cash", "USD", "ASSET");
            UUID transactionId = insertTransaction(conn, "zero-amount-tx-key-1");

            assertThatThrownBy(() ->
                    insertEntry(conn, transactionId, cashAccountId, BigDecimal.ZERO, "DEBIT"))
                    .isInstanceOf(SQLException.class);

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
            ps.setString(2, "schema constraint test");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID insertEntry(
            Connection conn, UUID transactionId, UUID accountId, BigDecimal amount, String direction)
            throws SQLException {
        // All test accounts here are USD, and the app's own base currency
        // default is USD (see application.yml ledger.fx.base-currency), so
        // this is exactly the identity conversion: base_currency_amount ==
        // amount, fx_rate_used == 1. See the class javadoc for why these
        // raw-SQL tests must populate the FX columns at all post-V9.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entries "
                        + "(transaction_id, account_id, amount, direction, base_currency_amount, "
                        + "fx_rate_used, fx_rate_effective_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now()) RETURNING id")) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, direction);
            ps.setBigDecimal(5, amount);
            ps.setBigDecimal(6, BigDecimal.ONE);
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
