package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateAudit;
import com.afrotransact.payments.domain.NegotiatedRateType;
import com.afrotransact.payments.port.NegotiatedRateRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNegotiatedRateRepository implements NegotiatedRateRepository {

    private final JdbcClient jdbc;

    JdbcNegotiatedRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NegotiatedRate r) {
        jdbc.sql("""
                insert into negotiated_rate (id, org_id, type, category, rate_bps, starts_at, ends_at, reason,
                    negotiated_by, reference, created_by, created_at, version, amount_minor)
                values (:id, :org, :type, :category, :bps, :starts, :ends, :reason, :negotiatedBy, :reference,
                    :createdBy, :createdAt, 0, :amount)
                """)
                .param("id", r.id()).param("org", r.orgId()).param("type", r.type().name())
                .param("category", r.category()).param("bps", r.rateBps()).param("starts", Sql.ts(r.startsAt()))
                .param("ends", Sql.ts(r.endsAt())).param("reason", r.reason())
                .param("negotiatedBy", r.negotiatedBy()).param("reference", r.reference())
                .param("createdBy", r.createdBy()).param("createdAt", Sql.ts(r.createdAt()))
                .param("amount", r.amountMinor()).update();
    }

    @Override
    public NegotiatedRate update(NegotiatedRate r) {
        int n = jdbc.sql("""
                update negotiated_rate set ended_at = :endedAt, ended_by = :endedBy, end_reason = :endReason,
                    expired_at = :expiredAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("endedAt", Sql.ts(r.endedAt())).param("endedBy", r.endedBy())
                .param("endReason", r.endReason()).param("expiredAt", Sql.ts(r.expiredAt()))
                .param("id", r.id()).param("version", r.version())
                .update();
        if (n != 1) {
            throw new OptimisticLockingFailureException("Negotiated rate " + r.id() + " changed concurrently");
        }
        return new NegotiatedRate(r.id(), r.orgId(), r.type(), r.category(), r.rateBps(), r.startsAt(), r.endsAt(),
                r.reason(), r.negotiatedBy(), r.reference(), r.createdBy(), r.createdAt(), r.endedAt(), r.endedBy(),
                r.endReason(), r.expiredAt(), r.version() + 1, r.amountMinor());
    }

    @Override
    public Optional<NegotiatedRate> findById(UUID id) {
        return jdbc.sql("select * from negotiated_rate where id = :id").param("id", id)
                .query(JdbcNegotiatedRateRepository::row).optional();
    }

    @Override
    public List<NegotiatedRate> lockForOrg(String orgId, NegotiatedRateType type) {
        return jdbc.sql("select * from negotiated_rate where org_id = :org and type = :type order by starts_at "
                        + "for update")
                .param("org", orgId).param("type", type.name()).query(JdbcNegotiatedRateRepository::row).list();
    }

    @Override
    public List<NegotiatedRate> forOrgs(Collection<String> orgIds) {
        if (orgIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select * from negotiated_rate
                 where org_id in (:orgs) and (ended_at is null or ended_at > starts_at)
                 order by org_id, starts_at
                """).param("orgs", List.copyOf(orgIds)).query(JdbcNegotiatedRateRepository::row).list();
    }

    @Override
    public List<NegotiatedRate> search(String orgId, NegotiatedRateType type, int limit) {
        StringBuilder sql = new StringBuilder("select * from negotiated_rate where true");
        if (orgId != null) {
            sql.append(" and org_id = :org");
        }
        if (type != null) {
            sql.append(" and type = :type");
        }
        sql.append(" order by created_at desc, id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (orgId != null) {
            spec = spec.param("org", orgId);
        }
        if (type != null) {
            spec = spec.param("type", type.name());
        }
        return spec.query(JdbcNegotiatedRateRepository::row).list();
    }

    @Override
    public List<NegotiatedRate> dueForExpiry(Instant now, int limit) {
        return jdbc.sql("""
                select * from negotiated_rate
                 where expired_at is null and ended_at is null and ends_at is not null and ends_at <= :now
                 order by ends_at, id limit :limit for update skip locked
                """).param("now", Sql.ts(now)).param("limit", limit).query(JdbcNegotiatedRateRepository::row).list();
    }

    @Override
    public void insertAudit(NegotiatedRateAudit e) {
        jdbc.sql("""
                insert into negotiated_rate_audit (id, rate_id, action, actor, reason, at)
                values (:id, :rate, :action, :actor, :reason, :at)
                """)
                .param("id", e.id()).param("rate", e.rateId()).param("action", e.action()).param("actor", e.actor())
                .param("reason", e.reason()).param("at", Sql.ts(e.at()))
                .update();
    }

    @Override
    public List<NegotiatedRateAudit> audit(UUID rateId) {
        return jdbc.sql("select * from negotiated_rate_audit where rate_id = :id order by at, id").param("id", rateId)
                .query((rs, n) -> new NegotiatedRateAudit(rs.getObject("id", UUID.class),
                        rs.getObject("rate_id", UUID.class), rs.getString("action"), rs.getString("actor"),
                        rs.getString("reason"), Sql.instant(rs, "at")))
                .list();
    }

    private static NegotiatedRate row(ResultSet rs, int n) throws SQLException {
        return new NegotiatedRate(rs.getObject("id", UUID.class), rs.getString("org_id"),
                NegotiatedRateType.valueOf(rs.getString("type")), rs.getString("category"), rs.getInt("rate_bps"),
                Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), rs.getString("reason"),
                rs.getString("negotiated_by"), rs.getString("reference"), rs.getString("created_by"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "ended_at"), rs.getString("ended_by"),
                rs.getString("end_reason"), Sql.instant(rs, "expired_at"), rs.getLong("version"),
                (Long) rs.getObject("amount_minor"));
    }
}
