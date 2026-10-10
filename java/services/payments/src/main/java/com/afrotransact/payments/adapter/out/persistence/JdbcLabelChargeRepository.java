package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.LabelCharge;
import com.afrotransact.payments.domain.LabelChargeStatus;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.LabelChargeRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLabelChargeRepository implements LabelChargeRepository {

    private final JdbcClient jdbc;

    JdbcLabelChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(LabelCharge c) {
        return jdbc.sql("""
                insert into label_charge (id, label_id, org_id, shipment_id, order_id, carrier, service,
                    tracking_number, currency, cost_minor, handling_minor, status, charge_journal_id,
                    credit_journal_id, source_event_id, purchased_at, charged_at, credited_at, version)
                values (:id, :label, :org, :shipment, :order, :carrier, :service, :tracking, :currency, :cost,
                    :handling, :status, :chargeJournal, :creditJournal, :event, :purchased, :charged, :credited, 0)
                on conflict (label_id) do nothing
                """)
                .param("id", c.id()).param("label", c.labelId()).param("org", c.orgId())
                .param("shipment", c.shipmentId()).param("order", c.orderId()).param("carrier", c.carrier())
                .param("service", c.service()).param("tracking", c.trackingNumber())
                .param("currency", c.cost().currency()).param("cost", c.cost().minor())
                .param("handling", c.handling().minor()).param("status", c.status().name())
                .param("chargeJournal", c.chargeJournal()).param("creditJournal", c.creditJournal())
                .param("event", c.sourceEventId()).param("purchased", Sql.ts(c.purchasedAt()))
                .param("charged", Sql.ts(c.chargedAt())).param("credited", Sql.ts(c.creditedAt()))
                .update() == 1;
    }

    @Override
    public Optional<LabelCharge> lockByLabel(String labelId) {
        return jdbc.sql("select * from label_charge where label_id = :l for update").param("l", labelId)
                .query(JdbcLabelChargeRepository::charge).optional();
    }

    @Override
    public LabelCharge update(LabelCharge c) {
        int updated = jdbc.sql("""
                update label_charge set status = :status, credit_journal_id = :creditJournal,
                    credited_at = :credited, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", c.status().name()).param("creditJournal", c.creditJournal())
                .param("credited", Sql.ts(c.creditedAt())).param("id", c.id()).param("version", c.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Label charge " + c.id() + " was changed concurrently");
        }
        return new LabelCharge(c.id(), c.labelId(), c.orgId(), c.shipmentId(), c.orderId(), c.carrier(),
                c.service(), c.trackingNumber(), c.cost(), c.handling(), c.status(), c.chargeJournal(),
                c.creditJournal(), c.sourceEventId(), c.purchasedAt(), c.chargedAt(), c.creditedAt(),
                c.version() + 1);
    }

    @Override
    public List<LabelCharge> ofOrg(String orgId, int limit) {
        return jdbc.sql("""
                select * from label_charge where org_id = :org and status <> 'VOIDED'
                order by purchased_at desc, id limit :limit
                """)
                .param("org", orgId).param("limit", limit).query(JdbcLabelChargeRepository::charge).list();
    }

    private static LabelCharge charge(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new LabelCharge(rs.getObject("id", UUID.class), rs.getString("label_id"), rs.getString("org_id"),
                rs.getString("shipment_id"), rs.getString("order_id"), rs.getString("carrier"),
                rs.getString("service"), rs.getString("tracking_number"),
                Money.of(rs.getLong("cost_minor"), currency), Money.of(rs.getLong("handling_minor"), currency),
                LabelChargeStatus.valueOf(rs.getString("status")), rs.getObject("charge_journal_id", UUID.class),
                rs.getObject("credit_journal_id", UUID.class), rs.getObject("source_event_id", UUID.class),
                Sql.instant(rs, "purchased_at"), Sql.instant(rs, "charged_at"), Sql.instant(rs, "credited_at"),
                rs.getLong("version"));
    }
}
