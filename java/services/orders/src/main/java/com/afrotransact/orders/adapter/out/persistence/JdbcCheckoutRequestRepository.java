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
        int inserted = jdbc.sql("""
                insert into checkout_request (customer_id, idem_key, fingerprint, order_id, order_number, workflow_id,
                    status, created_at, updated_at)
                values (:customer, :key, :fp, :orderId, :number, :workflow, :status, :now, :now)
                on conflict do nothing
                """)
                .param("customer", r.customerId()).param("key", r.idempotencyKey()).param("fp", r.fingerprint())
                .param("orderId", Sql.uuid(r.orderId())).param("number", r.orderNumber())
                .param("workflow", r.workflowId()).param("status", r.status())
                .param("now", Sql.ts(r.createdAt() == null ? Instant.now() : r.createdAt()))
                .update();
        if (inserted == 1) {
            return new Claim(true, null);
        }
        return new Claim(false, find(r.customerId(), r.idempotencyKey()).orElse(null));
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
                    updated_at = now()
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
