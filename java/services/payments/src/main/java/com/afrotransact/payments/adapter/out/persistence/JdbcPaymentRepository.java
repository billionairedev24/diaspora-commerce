package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AppliedRate;
import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.Fulfilment;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.domain.RateSource;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundLine;
import com.afrotransact.payments.domain.RefundStatus;
import com.afrotransact.payments.port.PaymentRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPaymentRepository implements PaymentRepository {

    private static final String SELECT = """
            select id, order_id, customer_id, currency, amount_minor, status, stripe_payment_intent_id,
                   client_secret, fee_schedule_id, placed_at, captured_minor, refunded_minor, authorized_at,
                   failure_message, metadata::text as metadata, created_at, updated_at, version, parent_payment_id,
                   multicapture
            from payment
            """;
    private static final TypeReference<List<RefundLineRow>> REFUND_LINES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPaymentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean claim(Payment p) {
        String conflict = p.isOffSession() ? "on conflict (id) do nothing"
                : "on conflict (order_id) where parent_payment_id is null do nothing";
        int inserted = jdbc.sql("""
                insert into payment (id, order_id, customer_id, currency, amount_minor, status,
                    stripe_payment_intent_id, client_secret, fee_schedule_id, placed_at, captured_minor,
                    refunded_minor, authorized_at, failure_message, metadata, created_at, updated_at, version,
                    parent_payment_id, multicapture)
                values (:id, :orderId, :customerId, :currency, :amount, :status, :pi, :secret, :schedule, :placedAt,
                    :captured, :refunded, :authorizedAt, :failure, cast(:metadata as jsonb), :createdAt, :updatedAt, 0,
                    :parent, :multicapture)
                """ + conflict)
                .param("id", p.id()).param("parent", p.parentPaymentId()).param("orderId", p.orderId())
                .param("multicapture", p.multicapture())
                .param("customerId", p.customerId())
                .param("currency", p.currency()).param("amount", p.amount().minor())
                .param("status", p.status().name()).param("pi", p.stripePaymentIntentId())
                .param("secret", p.clientSecret()).param("schedule", p.feeScheduleId())
                .param("placedAt", Sql.ts(p.placedAt())).param("captured", p.captured().minor())
                .param("refunded", p.refunded().minor()).param("authorizedAt", Sql.ts(p.authorizedAt()))
                .param("failure", p.failureMessage()).param("metadata", json.write(p.metadata()))
                .param("createdAt", Sql.ts(p.createdAt())).param("updatedAt", Sql.ts(p.updatedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        int lineNo = 0;
        for (PaymentLine pl : p.lines()) {
            OrderLine l = pl.line();
            AppliedRate rate = l.rate();
            jdbc.sql("""
                    insert into payment_line (payment_id, line_no, line_id, seller_org, category, fulfilment,
                        quantity, items_minor, shipping_minor, tax_minor, tax_jurisdiction, shipping_to_platform,
                        shipping_tax_minor, delivery_fee_minor, fee_kind, rate_bps, rate_source, rate_plan_code,
                        negotiated_rate_id, negotiated_until, negotiated_fba_minor)
                    values (:pid, :no, :lineId, :org, :category, :fulfilment, :qty, :items, :shipping, :tax,
                        :jurisdiction, :toPlatform, :shippingTax, :deliveryFee, :kind, :rateBps, :rateSource,
                        :ratePlan, :negotiatedId, :negotiatedUntil, :negotiatedFba)
                    """)
                    .param("pid", p.id()).param("no", ++lineNo).param("lineId", l.lineId())
                    .param("org", l.sellerOrg()).param("category", l.category())
                    .param("fulfilment", l.fulfilment().name()).param("qty", l.quantity())
                    .param("items", l.items().minor()).param("shipping", l.shipping().minor())
                    .param("tax", l.tax().minor()).param("jurisdiction", l.taxJurisdiction())
                    .param("toPlatform", pl.shippingToPlatform()).param("shippingTax", l.shippingTax().minor())
                    .param("deliveryFee", l.deliveryFee().minor())
                    .param("kind", l.kind().name())
                    .param("rateBps", rate == null ? null : rate.bps())
                    .param("rateSource", rate == null ? null : rate.source().name())
                    .param("ratePlan", rate == null ? null : rate.planCode())
                    .param("negotiatedFba", rate == null ? null : rate.negotiatedFbaMinor())
                    .param("negotiatedId", rate == null ? null : rate.negotiatedRateId())
                    .param("negotiatedUntil", rate == null || rate.negotiatedUntil() == null ? null
                            : Sql.ts(rate.negotiatedUntil()))
                    .update();
        }
        return true;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return one(SELECT + " where id = :v", id);
    }

    @Override
    public Optional<Payment> findByOrderId(String orderId) {
        return one(SELECT + " where order_id = :v and parent_payment_id is null", orderId);
    }

    @Override
    public List<Payment> children(UUID parentPaymentId) {
        return jdbc.sql(SELECT + " where parent_payment_id = :v order by created_at, id").param("v", parentPaymentId)
                .query(this::header).list().stream().map(h -> h.toPayment(lines(h.id(), h.currency()))).toList();
    }

    @Override
    public Optional<Payment> findByIntentId(String intentId) {
        return intentId == null ? Optional.empty() : one(SELECT + " where stripe_payment_intent_id = :v", intentId);
    }

    @Override
    public Optional<Payment> lockById(UUID id) {
        return one(SELECT + " where id = :v for update", id);
    }

    @Override
    public Optional<Payment> lockByIntentId(String intentId) {
        return intentId == null ? Optional.empty()
                : one(SELECT + " where stripe_payment_intent_id = :v for update", intentId);
    }

    private Optional<Payment> one(String sql, Object value) {
        Optional<Header> header = jdbc.sql(sql).param("v", value).query(this::header).optional();
        return header.map(h -> h.toPayment(lines(h.id(), h.currency())));
    }

    @Override
    public Payment update(Payment p) {
        int updated = jdbc.sql("""
                update payment set status = :status, stripe_payment_intent_id = :pi, client_secret = :secret,
                    captured_minor = :captured, refunded_minor = :refunded, authorized_at = :authorizedAt,
                    failure_message = :failure, updated_at = :updatedAt, multicapture = :multicapture,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("multicapture", p.multicapture())
                .param("status", p.status().name()).param("pi", p.stripePaymentIntentId())
                .param("secret", p.clientSecret()).param("captured", p.captured().minor())
                .param("refunded", p.refunded().minor()).param("authorizedAt", Sql.ts(p.authorizedAt()))
                .param("failure", p.failureMessage()).param("updatedAt", Sql.ts(p.updatedAt()))
                .param("id", p.id()).param("version", p.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Payment " + p.id() + " was changed concurrently");
        }
        for (PaymentLine pl : p.lines()) {
            jdbc.sql("""
                    update payment_line set capture_id = :capture, commission_minor = :commission,
                        fba_fee_minor = :fba, shipping_to_platform = :toPlatform, refunded_items_minor = :ri,
                        refunded_shipping_minor = :rs, refunded_tax_minor = :rt, commission_reversed_minor = :cr,
                        delivery_fee_charged_minor = :dfc, delivery_fee_reversed_minor = :dfr
                    where payment_id = :pid and line_id = :lineId
                    """)
                    .param("capture", pl.captureId()).param("commission", pl.commission().minor())
                    .param("fba", pl.fbaFee().minor()).param("toPlatform", pl.shippingToPlatform())
                    .param("ri", pl.refundedItems().minor()).param("rs", pl.refundedShipping().minor())
                    .param("rt", pl.refundedTax().minor()).param("cr", pl.commissionReversed().minor())
                    .param("dfc", pl.deliveryFee().minor()).param("dfr", pl.deliveryFeeReversed().minor())
                    .param("pid", p.id()).param("lineId", pl.lineId())
                    .update();
        }
        return new Payment(p.id(), p.orderId(), p.customerId(), p.amount(), p.status(), p.stripePaymentIntentId(),
                p.clientSecret(), p.feeScheduleId(), p.placedAt(), p.captured(), p.refunded(), p.authorizedAt(),
                p.failureMessage(), p.metadata(), p.lines(), p.createdAt(), p.updatedAt(), p.version() + 1,
                p.parentPaymentId(), p.multicapture());
    }

    private List<PaymentLine> lines(UUID paymentId, String currency) {
        return jdbc.sql("select * from payment_line where payment_id = :pid order by line_no")
                .param("pid", paymentId)
                .query((rs, n) -> {
                    OrderLine line = new OrderLine(rs.getString("line_id"), rs.getString("seller_org"),
                            rs.getString("category"), Fulfilment.valueOf(rs.getString("fulfilment")),
                            rs.getInt("quantity"), Money.of(rs.getLong("items_minor"), currency),
                            Money.of(rs.getLong("shipping_minor"), currency),
                            Money.of(rs.getLong("tax_minor"), currency), rs.getString("tax_jurisdiction"),
                            Money.of(rs.getLong("shipping_tax_minor"), currency),
                            Money.of(rs.getLong("delivery_fee_minor"), currency),
                            FeeKind.valueOf(rs.getString("fee_kind")), rate(rs));
                    return new PaymentLine(line, rs.getObject("capture_id", UUID.class),
                            Money.of(rs.getLong("commission_minor"), currency),
                            Money.of(rs.getLong("fba_fee_minor"), currency), rs.getBoolean("shipping_to_platform"),
                            Money.of(rs.getLong("refunded_items_minor"), currency),
                            Money.of(rs.getLong("refunded_shipping_minor"), currency),
                            Money.of(rs.getLong("refunded_tax_minor"), currency),
                            Money.of(rs.getLong("commission_reversed_minor"), currency),
                            Money.of(rs.getLong("delivery_fee_charged_minor"), currency),
                            Money.of(rs.getLong("delivery_fee_reversed_minor"), currency));
                })
                .list();
    }

    /** The rate snapshotted on a line, or null for lines created before rates were snapshotted. */
    private static AppliedRate rate(ResultSet rs) throws SQLException {
        int bps = rs.getInt("rate_bps");
        if (rs.wasNull()) {
            return null;
        }
        return new AppliedRate(FeeKind.valueOf(rs.getString("fee_kind")), bps,
                RateSource.valueOf(rs.getString("rate_source")), rs.getString("category"),
                rs.getString("rate_plan_code"), rs.getObject("negotiated_rate_id", UUID.class),
                Sql.instant(rs, "negotiated_until"), (Long) rs.getObject("negotiated_fba_minor"));
    }

    private Header header(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new Header(rs.getObject("id", UUID.class), rs.getString("order_id"), rs.getString("customer_id"),
                currency, rs.getLong("amount_minor"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("stripe_payment_intent_id"), rs.getString("client_secret"),
                rs.getObject("fee_schedule_id", UUID.class), Sql.instant(rs, "placed_at"),
                rs.getLong("captured_minor"), rs.getLong("refunded_minor"), Sql.instant(rs, "authorized_at"),
                rs.getString("failure_message"), json.stringMap(rs.getString("metadata")),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"),
                rs.getObject("parent_payment_id", UUID.class), rs.getBoolean("multicapture"));
    }

    @Override
    public void insertCapture(Capture c) {
        jdbc.sql("""
                insert into payment_capture (id, payment_id, shipment_id, amount_minor, stripe_fee_minor,
                    fee_estimated, stripe_charge_id, final_capture, line_ids, journal_id, captured_at)
                values (:id, :pid, :shipment, :amount, :fee, :estimated, :charge, :final, cast(:lines as jsonb),
                    :journal, :at)
                """)
                .param("id", c.id()).param("pid", c.paymentId()).param("shipment", c.shipmentId())
                .param("amount", c.amount().minor()).param("fee", c.stripeFee().minor())
                .param("estimated", c.feeEstimated()).param("charge", c.stripeChargeId())
                .param("final", c.finalCapture()).param("lines", json.write(c.lineIds()))
                .param("journal", c.journalId()).param("at", Sql.ts(c.capturedAt()))
                .update();
    }

    private static final String CAPTURE_SELECT = """
            select c.*, c.line_ids::text as line_ids_json, p.currency from payment_capture c
            join payment p on p.id = c.payment_id
            """;

    @Override
    public Optional<Capture> findCapture(UUID captureId) {
        return jdbc.sql(CAPTURE_SELECT + " where c.id = :id").param("id", captureId).query(this::capture).optional();
    }

    @Override
    public Optional<Capture> findCapture(UUID paymentId, String shipmentId) {
        return jdbc.sql(CAPTURE_SELECT + " where c.payment_id = :pid and c.shipment_id = :s")
                .param("pid", paymentId).param("s", shipmentId).query(this::capture).optional();
    }

    @Override
    public List<Capture> captures(UUID paymentId) {
        return jdbc.sql(CAPTURE_SELECT + " where c.payment_id = :pid order by c.captured_at, c.id")
                .param("pid", paymentId).query(this::capture).list();
    }

    private Capture capture(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new Capture(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("shipment_id"), Money.of(rs.getLong("amount_minor"), currency),
                Money.of(rs.getLong("stripe_fee_minor"), currency), rs.getBoolean("fee_estimated"),
                rs.getString("stripe_charge_id"), rs.getBoolean("final_capture"),
                json.strings(rs.getString("line_ids_json")), rs.getObject("journal_id", UUID.class),
                Sql.instant(rs, "captured_at"));
    }

    @Override
    public void insertRefund(Refund r) {
        jdbc.sql("""
                insert into payment_refund (id, payment_id, order_id, stripe_refund_id, currency, amount_minor,
                    status, reason, lines, created_at, settled_at)
                values (:id, :pid, :orderId, :stripeId, :currency, :amount, :status, :reason, cast(:lines as jsonb),
                    :createdAt, :settledAt)
                """)
                .param("id", r.id()).param("pid", r.paymentId()).param("orderId", r.orderId())
                .param("stripeId", r.stripeRefundId()).param("currency", r.amount().currency())
                .param("amount", r.amount().minor()).param("status", r.status().name())
                .param("reason", r.reason()).param("lines", json.write(r.lines().stream().map(RefundLineRow::of)
                        .toList()))
                .param("createdAt", Sql.ts(r.createdAt())).param("settledAt", Sql.ts(r.settledAt()))
                .update();
    }

    @Override
    public void updateRefund(Refund r) {
        jdbc.sql("update payment_refund set status = :status, settled_at = :settledAt where id = :id")
                .param("status", r.status().name()).param("settledAt", Sql.ts(r.settledAt())).param("id", r.id())
                .update();
    }

    @Override
    public Optional<Refund> findRefund(UUID refundId) {
        return jdbc.sql("select *, lines::text as lines_json from payment_refund where id = :id")
                .param("id", refundId).query(this::refund).optional();
    }

    @Override
    public List<Refund> refunds(UUID paymentId) {
        return jdbc.sql("select *, lines::text as lines_json from payment_refund where payment_id = :pid "
                        + "order by created_at, id")
                .param("pid", paymentId).query(this::refund).list();
    }

    private Refund refund(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        List<RefundLine> lines = json.read(rs.getString("lines_json"), REFUND_LINES).stream()
                .map(row -> row.toLine(currency)).toList();
        return new Refund(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("order_id"), rs.getString("stripe_refund_id"),
                Money.of(rs.getLong("amount_minor"), currency), RefundStatus.valueOf(rs.getString("status")),
                rs.getString("reason"), lines, Sql.instant(rs, "created_at"), Sql.instant(rs, "settled_at"));
    }

    /** Stored form of a refund line (plain numbers). */
    record RefundLineRow(String lineId, String sellerOrg, String taxJurisdiction, boolean shippingToPlatform,
                         long itemsMinor, long shippingMinor, long taxMinor, long commissionReversedMinor,
                         long deliveryFeeReversedMinor, Boolean preorderDeliveryFee) {

        static RefundLineRow of(RefundLine l) {
            return new RefundLineRow(l.lineId(), l.sellerOrg(), l.taxJurisdiction(), l.shippingToPlatform(),
                    l.items().minor(), l.shipping().minor(), l.tax().minor(), l.commissionReversed().minor(),
                    l.deliveryFeeReversed().minor(), l.preorderDeliveryFee());
        }

        RefundLine toLine(String currency) {
            return new RefundLine(lineId, sellerOrg, taxJurisdiction, shippingToPlatform,
                    Money.of(itemsMinor, currency), Money.of(shippingMinor, currency), Money.of(taxMinor, currency),
                    Money.of(commissionReversedMinor, currency), Money.of(deliveryFeeReversedMinor, currency),
                    Boolean.TRUE.equals(preorderDeliveryFee));
        }
    }

    private record Header(UUID id, String orderId, String customerId, String currency, long amount,
                          PaymentStatus status, String intentId, String clientSecret, UUID scheduleId,
                          Instant placedAt, long captured, long refunded, Instant authorizedAt,
                          String failure, Map<String, String> metadata, Instant createdAt,
                          Instant updatedAt, long version, UUID parentId, boolean multicapture) {

        Payment toPayment(List<PaymentLine> lines) {
            return new Payment(id, orderId, customerId, Money.of(amount, currency), status, intentId, clientSecret,
                    scheduleId, placedAt, Money.of(captured, currency), Money.of(refunded, currency), authorizedAt,
                    failure, metadata, lines, createdAt, updatedAt, version, parentId, multicapture);
        }
    }
}
