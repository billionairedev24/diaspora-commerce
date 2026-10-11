package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.orders.port.CheckoutRequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT … ON CONFLICT DO NOTHING}: the first request claims (customer, key); a concurrent duplicate waits on
 * the primary key until the first commits and then reads it.
 */
@Repository
class JdbcCheckoutRequestRepository implements CheckoutRequestRepository {

    private static final String COLUMNS = """
            customer_id, idem_key, fingerprint, order_id, order_number, workflow_id, status, payment_id, client_secret,
            failure_code, failure_message, created_at""";

    private final JdbcClient jdbc;

    JdbcCheckoutRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(CheckoutRequest r) {
        if (insert(r, false)) {
            return new Claim(true, null);
        }
        return new Claim(false, find(r.customerId(), r.idempotencyKey()).orElse(null));
    }

    /**
     * {@code ON CONFLICT DO NOTHING} also covers the partial unique index on the open-checkout slot: a concurrent
     * second checkout of the customer waits for the first to commit, then finds it as {@code open}.
     */
    @Override
    public Claim claimCheckout(CheckoutRequest r) {
        if (insert(r, true)) {
            return new Claim(true, null);
        }
        Optional<CheckoutRequest> same = find(r.customerId(), r.idempotencyKey());
        if (same.isPresent()) {
            return new Claim(false, same.get());
        }
        return new Claim(false, null, findOpen(r.customerId()).orElse(null));
    }

    private boolean insert(CheckoutRequest r, boolean open) {
        return jdbc.sql("""
                insert into checkout_request (customer_id, idem_key, fingerprint, order_id, order_number, workflow_id,
                    status, open_checkout, created_at, updated_at)
                values (:customer, :key, :fp, :orderId, :number, :workflow, :status, :open, :now, :now)
                on conflict do nothing
                """)
                .param("customer", r.customerId()).param("key", r.idempotencyKey()).param("fp", r.fingerprint())
                .param("orderId", Sql.uuid(r.orderId())).param("number", r.orderNumber())
                .param("workflow", r.workflowId()).param("status", r.status()).param("open", open)
                .param("now", Sql.ts(r.createdAt() == null ? Instant.now() : r.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<CheckoutRequest> findOpen(String customerId) {
        return jdbc.sql("select " + COLUMNS + " from checkout_request where customer_id = :c and open_checkout")
                .param("c", customerId).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void lockLateAdds(String customerId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "late-add|" + customerId)
                .query((rs, n) -> 1).list();
    }

    @Override
    public void markLateAddGuard(String orderId, String guard) {
        jdbc.sql("update checkout_request set late_add_guard = :g where order_id = :o")
                .param("g", guard).param("o", Sql.uuid(orderId)).update();
    }

    @Override
    public Optional<CheckoutRequest> recentLateAdd(String customerId, String guard, Instant since) {
        return jdbc.sql("select " + COLUMNS + """
                 from checkout_request
                where customer_id = :c and late_add_guard = :g and created_at >= :since
                  and status in ('STARTED', 'AWAITING_PAYMENT', 'PLACED')
                order by created_at desc limit 1
                """)
                .param("c", customerId).param("g", guard).param("since", Sql.ts(since))
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<CheckoutRequest> find(String customerId, String idempotencyKey) {
        return jdbc.sql("select " + COLUMNS + " from checkout_request where customer_id = :c and idem_key = :k")
                .param("c", customerId).param("k", idempotencyKey).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<CheckoutRequest> findByOrderId(String orderId) {
        return jdbc.sql("select " + COLUMNS + " from checkout_request where order_id = :o")
                .param("o", Sql.uuid(orderId)).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<CheckoutRequest> findByNumber(String orderNumber) {
        return jdbc.sql("select " + COLUMNS + " from checkout_request where order_number = :n")
                .param("n", orderNumber).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void markAwaitingPayment(String orderId, String paymentId, String clientSecret) {
        jdbc.sql("""
                update checkout_request set status = 'AWAITING_PAYMENT', payment_id = :payment,
                    client_secret = :secret, updated_at = now()
                where order_id = :o and status = 'STARTED'
                """)
                .param("payment", paymentId).param("secret", clientSecret).param("o", Sql.uuid(orderId))
                .update();
    }

    @Override
    public void markOutcome(String orderId, String status, String failureCode, String failureMessage) {
        jdbc.sql("""
                update checkout_request set status = :status, failure_code = :code, failure_message = :message,
                    open_checkout = false, updated_at = now()
                where order_id = :o
                """)
                .param("status", status).param("code", failureCode).param("message", failureMessage)
                .param("o", Sql.uuid(orderId))
                .update();
    }

    @Override
    public void release(String customerId, String idempotencyKey) {
        jdbc.sql("delete from checkout_request where customer_id = :c and idem_key = :k and status = 'STARTED'")
                .param("c", customerId).param("k", idempotencyKey).update();
    }

    private static CheckoutRequest map(ResultSet rs) throws SQLException {
        return new CheckoutRequest(rs.getString("customer_id"), rs.getString("idem_key"), rs.getString("fingerprint"),
                Sql.str(rs, "order_id"), rs.getString("order_number"), rs.getString("workflow_id"),
                rs.getString("status"), rs.getString("payment_id"), rs.getString("client_secret"),
                rs.getString("failure_code"), rs.getString("failure_message"), Sql.instant(rs, "created_at"));
    }
}
