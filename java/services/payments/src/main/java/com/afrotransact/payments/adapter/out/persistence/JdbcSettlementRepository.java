package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.payments.domain.SettlementStatus;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.ShipmentSettlement;
import com.afrotransact.payments.port.SettlementRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSettlementRepository implements SettlementRepository {

    private final JdbcClient jdbc;

    JdbcSettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Settlement s) {
        return jdbc.sql("""
                insert into settlement (order_id, kind, delivered_at, release_at, status, next_check_at, updated_at)
                values (:orderId, :kind, :deliveredAt, :releaseAt, :status, :releaseAt, :updatedAt)
                on conflict (order_id) do nothing
                """)
                .param("orderId", s.orderId()).param("kind", s.kind().name())
                .param("deliveredAt", Sql.ts(s.deliveredAt())).param("releaseAt", Sql.ts(s.releaseAt()))
                .param("status", s.status().name()).param("updatedAt", Sql.ts(s.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<Settlement> find(String orderId) {
        return jdbc.sql("select * from settlement where order_id = :o").param("o", orderId)
                .query(JdbcSettlementRepository::settlement).optional();
    }

    @Override
    public Optional<Settlement> lockNextMatured(Instant now) {
        return jdbc.sql("""
                select * from settlement
                where status = 'OPEN' and release_at <= :now and next_check_at <= :now
                order by next_check_at, order_id
                limit 1
                for update skip locked
                """)
                .param("now", Sql.ts(now)).query(JdbcSettlementRepository::settlement).optional();
    }

    @Override
    public void markChecked(String orderId, boolean settled, Instant nextCheckAt, Instant now) {
        jdbc.sql("""
                update settlement set status = :status, next_check_at = coalesce(:next, next_check_at),
                    updated_at = :now
                where order_id = :o
                """)
                .param("status", settled ? SettlementStatus.SETTLED.name() : SettlementStatus.OPEN.name())
                .param("next", Sql.ts(nextCheckAt)).param("now", Sql.ts(now)).param("o", orderId)
                .update();
    }

    @Override
    public void reopen(String orderId, Instant now) {
        jdbc.sql("""
                update settlement set status = 'OPEN', next_check_at = release_at, updated_at = :now
                where order_id = :o and status = 'SETTLED'
                """).param("now", Sql.ts(now)).param("o", orderId).update();
    }

    @Override
    public boolean insertShipment(ShipmentSettlement s) {
        return jdbc.sql("""
                insert into shipment_settlement (order_id, shipment_id, kind, delivered_at, release_at, status,
                    next_check_at, updated_at)
                values (:orderId, :shipmentId, :kind, :deliveredAt, :releaseAt, :status, :releaseAt, :updatedAt)
                on conflict (order_id, shipment_id) do nothing
                """)
                .param("orderId", s.orderId()).param("shipmentId", s.shipmentId()).param("kind", s.kind().name())
                .param("deliveredAt", Sql.ts(s.deliveredAt())).param("releaseAt", Sql.ts(s.releaseAt()))
                .param("status", s.status().name()).param("updatedAt", Sql.ts(s.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<ShipmentSettlement> findShipment(String orderId, String shipmentId) {
        return jdbc.sql("select * from shipment_settlement where order_id = :o and shipment_id = :s")
                .param("o", orderId).param("s", shipmentId)
                .query(JdbcSettlementRepository::shipmentSettlement).optional();
    }

    @Override
    public List<ShipmentSettlement> shipments(String orderId) {
        return jdbc.sql("select * from shipment_settlement where order_id = :o order by delivered_at, shipment_id")
                .param("o", orderId).query(JdbcSettlementRepository::shipmentSettlement).list();
    }

    @Override
    public Optional<ShipmentSettlement> lockNextMaturedShipment(Instant now) {
        return jdbc.sql("""
                select * from shipment_settlement
                where status = 'OPEN' and release_at <= :now and next_check_at <= :now
                order by next_check_at, order_id, shipment_id
                limit 1
                for update skip locked
                """)
                .param("now", Sql.ts(now)).query(JdbcSettlementRepository::shipmentSettlement).optional();
    }

    @Override
    public void markShipmentChecked(String orderId, String shipmentId, boolean settled, Instant nextCheckAt,
                                    Instant now) {
        jdbc.sql("""
                update shipment_settlement set status = :status, next_check_at = coalesce(:next, next_check_at),
                    updated_at = :now
                where order_id = :o and shipment_id = :s
                """)
                .param("status", settled ? SettlementStatus.SETTLED.name() : SettlementStatus.OPEN.name())
                .param("next", Sql.ts(nextCheckAt)).param("now", Sql.ts(now)).param("o", orderId)
                .param("s", shipmentId)
                .update();
    }

    @Override
    public void insertTransfer(SettlementTransfer t) {
        jdbc.sql("""
                insert into settlement_transfer (id, order_id, org_id, stripe_transfer_id, currency, amount_minor,
                    reversed_minor, created_at)
                values (:id, :orderId, :org, :stripeId, :currency, :amount, :reversed, :at)
                """)
                .param("id", t.id()).param("orderId", t.orderId()).param("org", t.orgId())
                .param("stripeId", t.stripeTransferId()).param("currency", t.amount().currency())
                .param("amount", t.amount().minor()).param("reversed", t.reversed().minor())
                .param("at", Sql.ts(t.createdAt()))
                .update();
    }

    @Override
    public void updateTransfer(SettlementTransfer t) {
        jdbc.sql("update settlement_transfer set reversed_minor = :reversed where id = :id")
                .param("reversed", t.reversed().minor()).param("id", t.id()).update();
    }

    @Override
    public List<SettlementTransfer> transfers(String orderId, String orgId) {
        return jdbc.sql("select * from settlement_transfer where order_id = :o and org_id = :org "
                        + "order by created_at, id")
                .param("o", orderId).param("org", orgId).query(JdbcSettlementRepository::transfer).list();
    }

    @Override
    public List<SettlementTransfer> lockTransfers(String orderId, String orgId) {
        return jdbc.sql("select * from settlement_transfer where order_id = :o and org_id = :org "
                        + "order by created_at desc, id for update")
                .param("o", orderId).param("org", orgId).query(JdbcSettlementRepository::transfer).list();
    }

    @Override
    public Optional<SettlementTransfer> lockTransferByStripeId(String stripeTransferId) {
        return jdbc.sql("select * from settlement_transfer where stripe_transfer_id = :s for update")
                .param("s", stripeTransferId).query(JdbcSettlementRepository::transfer).optional();
    }

    @Override
    public boolean reversalExists(String stripeReversalId) {
        return jdbc.sql("select count(*) from transfer_reversal where stripe_reversal_id = :s")
                .param("s", stripeReversalId).query(Long.class).single() > 0;
    }

    @Override
    public void insertReversal(UUID transferId, String stripeReversalId, Money amount, UUID refundId, Instant at) {
        jdbc.sql("""
                insert into transfer_reversal (id, transfer_id, stripe_reversal_id, amount_minor, refund_id, created_at)
                values (:id, :tid, :sid, :amount, :refund, :at)
                """)
                .param("id", UUID.randomUUID()).param("tid", transferId).param("sid", stripeReversalId)
                .param("amount", amount.minor()).param("refund", refundId).param("at", Sql.ts(at))
                .update();
    }

    @Override
    public Money transferredTo(String orgId, String currency) {
        Long sum = jdbc.sql("select coalesce(sum(amount_minor - reversed_minor), 0) from settlement_transfer "
                        + "where org_id = :org and currency = :currency")
                .param("org", orgId).param("currency", currency).query(Long.class).single();
        return Money.of(sum, currency);
    }

    private static Settlement settlement(ResultSet rs, int n) throws SQLException {
        return new Settlement(rs.getString("order_id"), SettlementKind.valueOf(rs.getString("kind")),
                Sql.instant(rs, "delivered_at"), Sql.instant(rs, "release_at"),
                SettlementStatus.valueOf(rs.getString("status")), Sql.instant(rs, "updated_at"));
    }

    private static ShipmentSettlement shipmentSettlement(ResultSet rs, int n) throws SQLException {
        return new ShipmentSettlement(rs.getString("order_id"), rs.getString("shipment_id"),
                SettlementKind.valueOf(rs.getString("kind")), Sql.instant(rs, "delivered_at"),
                Sql.instant(rs, "release_at"), SettlementStatus.valueOf(rs.getString("status")),
                Sql.instant(rs, "updated_at"));
    }

    private static SettlementTransfer transfer(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new SettlementTransfer(rs.getObject("id", UUID.class), rs.getString("order_id"),
                rs.getString("org_id"), rs.getString("stripe_transfer_id"),
                Money.of(rs.getLong("amount_minor"), currency), Money.of(rs.getLong("reversed_minor"), currency),
                Sql.instant(rs, "created_at"));
    }
}
