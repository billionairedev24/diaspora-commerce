package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.domain.ClaimType;
import com.afrotransact.trustops.domain.Sla;
import com.afrotransact.trustops.port.ClaimHistoryEntry;
import com.afrotransact.trustops.port.ClaimQuery;
import com.afrotransact.trustops.port.ClaimRepository;
import com.afrotransact.trustops.port.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcClaimRepository implements ClaimRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcClaimRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String nextReference() {
        return "AZ-" + jdbc.sql("select nextval('claim_reference_seq')").query(Long.class).single();
    }

    @Override
    public Claim insert(Claim c) {
        try {
            jdbc.sql("""
                    insert into claim (id, reference, type, status, order_id, order_number, shipment_id, seller_org_id,
                        customer_id, amount_minor, currency, description, evidence, sla_started_at, due_at, opened_at,
                        updated_at, version)
                    values (:id, :ref, :type, :status, :orderId, :orderNumber, :shipmentId, :seller, :customer, :amount,
                        :currency, :description, cast(:evidence as jsonb), :slaStart, :dueAt, :openedAt, :updatedAt, 0)
                    """)
                    .param("id", c.id())
                    .param("ref", c.reference())
                    .param("type", c.type().name())
                    .param("status", c.status().name())
                    .param("orderId", c.orderId())
                    .param("orderNumber", c.orderNumber())
                    .param("shipmentId", c.shipmentId())
                    .param("seller", c.sellerOrgId())
                    .param("customer", c.customerId())
                    .param("amount", c.amountMinor())
                    .param("currency", c.currency())
                    .param("description", c.description())
                    .param("evidence", json.write(c.evidence()))
                    .param("slaStart", Sql.ts(c.sla().startedAt()))
                    .param("dueAt", Sql.ts(c.sla().dueAt()))
                    .param("openedAt", Sql.ts(c.openedAt()))
                    .param("updatedAt", Sql.ts(c.updatedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("trustops.claim-already-open",
                    "There is already an open claim for this order / shipment");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Claim update(Claim c) {
        int rows = jdbc.sql("""
                update claim set status = :status, evidence = cast(:evidence as jsonb), seller_response = :response,
                    seller_responded_at = :respondedAt, sla_started_at = :slaStart, due_at = :dueAt,
                    escalated = :escalated, escalation_reason = :escReason, outcome = :outcome,
                    refund_minor = :refundMinor, refund_id = :refundId, decided_by = :decidedBy,
                    decided_at = :decidedAt, decision_note = :note, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", c.status().name())
                .param("evidence", json.write(c.evidence()))
                .param("response", c.sellerResponse())
                .param("respondedAt", Sql.ts(c.sellerRespondedAt()))
                .param("slaStart", Sql.ts(c.sla().startedAt()))
                .param("dueAt", Sql.ts(c.sla().dueAt()))
                .param("escalated", c.escalated())
                .param("escReason", c.escalationReason())
                .param("outcome", c.outcome() == null ? null : c.outcome().name())
                .param("refundMinor", c.refundMinor())
                .param("refundId", c.refundId())
                .param("decidedBy", c.decidedBy())
                .param("decidedAt", Sql.ts(c.decidedAt()))
                .param("note", c.decisionNote())
                .param("updatedAt", Sql.ts(c.updatedAt()))
                .param("id", c.id())
                .param("version", c.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("trustops.concurrent-update", "The claim changed; reload and retry");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Optional<Claim> findById(UUID id) {
        return jdbc.sql("select * from claim where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Page<Claim> search(ClaimQuery q, int page, int size) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.status() != null) {
            where.add("status = :status");
            params.put("status", q.status().name());
        } else if (q.openOnly()) {
            where.add("status in ('AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED')");
        }
        if (q.sellerOrgId() != null) {
            where.add("seller_org_id = :seller");
            params.put("seller", q.sellerOrgId());
        }
        if (q.customerId() != null) {
            where.add("customer_id = :customer");
            params.put("customer", q.customerId());
        }
        if (q.orderId() != null) {
            where.add("order_id = :orderId");
            params.put("orderId", q.orderId());
        }
        String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        String order = q.openOnly() ? " order by due_at" : " order by opened_at desc";
        long total = jdbc.sql("select count(*) from claim" + clause).params(params).query(Long.class).single();
        List<Claim> items = jdbc.sql("select * from claim" + clause + order + " limit :limit offset :offset")
                .params(params).param("limit", size).param("offset", Sql.offset(page, size)).query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public List<Claim> findBreached(ClaimStatus status, Instant now, int limit) {
        return jdbc.sql("select * from claim where status = :status and due_at <= :now order by due_at "
                        + "limit :limit for update skip locked")
                .param("status", status.name()).param("now", Sql.ts(now)).param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public void appendHistory(UUID claimId, ClaimHistoryEntry e) {
        jdbc.sql("""
                insert into claim_history (claim_id, at, actor_id, action, from_status, to_status, note)
                values (:claimId, :at, :actor, :action, :from, :to, :note)
                """)
                .param("claimId", claimId)
                .param("at", Sql.ts(e.at()))
                .param("actor", e.actorId())
                .param("action", e.action())
                .param("from", e.fromStatus() == null ? null : e.fromStatus().name())
                .param("to", e.toStatus().name())
                .param("note", e.note())
                .update();
    }

    @Override
    public List<ClaimHistoryEntry> history(UUID claimId) {
        return jdbc.sql("select * from claim_history where claim_id = :id order by id").param("id", claimId)
                .query((rs, row) -> {
                    String from = rs.getString("from_status");
                    return new ClaimHistoryEntry(Sql.instant(rs, "at"), rs.getString("actor_id"),
                            rs.getString("action"), from == null ? null : ClaimStatus.valueOf(from),
                            ClaimStatus.valueOf(rs.getString("to_status")), rs.getString("note"));
                }).list();
    }

    private Claim map(ResultSet rs, int row) throws SQLException {
        String outcome = rs.getString("outcome");
        long refund = rs.getLong("refund_minor");
        Long refundMinor = rs.wasNull() ? null : refund;
        return new Claim(rs.getObject("id", UUID.class), rs.getString("reference"),
                ClaimType.valueOf(rs.getString("type")), ClaimStatus.valueOf(rs.getString("status")),
                rs.getString("order_id"), rs.getString("order_number"), rs.getString("shipment_id"),
                rs.getString("seller_org_id"), rs.getString("customer_id"), rs.getLong("amount_minor"),
                rs.getString("currency"), rs.getString("description"), json.evidence(rs.getString("evidence")),
                rs.getString("seller_response"), Sql.instant(rs, "seller_responded_at"),
                new Sla(Sql.instant(rs, "sla_started_at"), Sql.instant(rs, "due_at")), rs.getBoolean("escalated"),
                rs.getString("escalation_reason"), outcome == null ? null : ClaimOutcome.valueOf(outcome),
                refundMinor, rs.getString("refund_id"), rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                rs.getString("decision_note"), Sql.instant(rs, "opened_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
