package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.port.PlanFeeCollectionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanFeeCollectionRepository implements PlanFeeCollectionRepository {

    static final String DEFAULT_KEY = "plan_fee_collection_default";

    private final JdbcClient jdbc;

    JdbcPlanFeeCollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PlatformDefault platformDefault() {
        return jdbc.sql("select value, updated_by, updated_at from billing_setting where key = :k")
                .param("k", DEFAULT_KEY)
                .query((rs, n) -> new PlatformDefault(PlanFeeCollection.valueOf(rs.getString("value")),
                        rs.getString("updated_by"), Sql.instant(rs, "updated_at")))
                .optional().orElse(new PlatformDefault(PlanFeeCollection.EARNINGS_FIRST, "system", null));
    }

    @Override
    public void setPlatformDefault(PlanFeeCollection mode, String actor, Instant at) {
        jdbc.sql("""
                insert into billing_setting (key, value, updated_by, updated_at) values (:k, :v, :a, :at)
                on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by,
                    updated_at = excluded.updated_at, version = billing_setting.version + 1
                """).param("k", DEFAULT_KEY).param("v", mode.name()).param("a", actor).param("at", Sql.ts(at))
                .update();
    }

    @Override
    public void recordChange(Change c) {
        jdbc.sql("""
                insert into plan_fee_collection_change (id, org_id, previous, mode, source, actor_id, reason_code,
                                                        note, applies_from, created_at)
                values (:id, :o, :p, :m, :s, :a, :r, :n, :from, :at)
                """).param("id", c.id()).param("o", c.orgId())
                .param("p", c.previous() == null ? null : c.previous().name()).param("m", c.mode().name())
                .param("s", c.source()).param("a", c.actorId()).param("r", c.reasonCode()).param("n", c.note())
                .param("from", Sql.ts(c.appliesFrom())).param("at", Sql.ts(c.createdAt())).update();
    }

    @Override
    public List<Change> changes(String orgId, int limit) {
        return jdbc.sql("select * from plan_fee_collection_change where org_id = :o order by created_at desc, id "
                        + "limit :l").param("o", orgId).param("l", limit).query(this::change).list();
    }

    private Change change(ResultSet rs, int n) throws SQLException {
        String previous = rs.getString("previous");
        return new Change(rs.getObject("id", UUID.class), rs.getString("org_id"),
                previous == null ? null : PlanFeeCollection.valueOf(previous),
                PlanFeeCollection.valueOf(rs.getString("mode")), rs.getString("source"), rs.getString("actor_id"),
                rs.getString("reason_code"), rs.getString("note"), Sql.instant(rs, "applies_from"),
                Sql.instant(rs, "created_at"));
    }
}
