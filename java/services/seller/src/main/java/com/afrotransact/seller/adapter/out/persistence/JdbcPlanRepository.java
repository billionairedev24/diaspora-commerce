package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanInput;
import com.afrotransact.seller.domain.PlanStatus;
import com.afrotransact.seller.domain.PlanVersion;
import com.afrotransact.seller.domain.PlanVersionStatus;
import com.afrotransact.seller.domain.PlanVisibility;
import com.afrotransact.seller.port.PlanRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPlanRepository implements PlanRepository {

    private static final TypeReference<Map<String, String>> LIMITS = new TypeReference<>() { };

    /** A plan joined with the prices of the version asked for (current by default). */
    private static final String SELECT = """
            select p.*, coalesce(v.version, 0) as priced_version, coalesce(v.trial_days, 0) as v_trial,
                   coalesce(max(case when pr.cadence = 'MONTHLY' then pr.amount_cents end), 0) as m_cents,
                   coalesce(max(case when pr.cadence = 'QUARTERLY' then pr.amount_cents end), 0) as q_cents,
                   coalesce(max(case when pr.cadence = 'ANNUAL' then pr.amount_cents end), 0) as a_cents
            from plan p
            left join plan_version v on v.plan_code = p.code and v.version = %s
            left join plan_version_price pr on pr.plan_code = v.plan_code and pr.version = v.version
            """;
    private static final String GROUP = " group by p.code, v.version, v.trial_days";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPlanRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Plan> findOpen(OrgKind kind) {
        String where = " where p.status = 'ACTIVE' and p.current_version is not null"
                + (kind == null ? "" : " and p.kind = :kind");
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT.formatted("p.current_version") + where + GROUP
                + " order by p.kind desc, p.sort_order, p.code");
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        return spec.query(this::map).list();
    }

    @Override
    public List<Plan> findAll() {
        return jdbc.sql(SELECT.formatted("p.current_version") + GROUP + " order by p.kind desc, p.sort_order, p.code")
                .query(this::map).list();
    }

    @Override
    public Optional<Plan> find(String code) {
        return jdbc.sql(SELECT.formatted("p.current_version") + " where p.code = :c" + GROUP).param("c", code)
                .query(this::map).optional();
    }

    @Override
    public Optional<Plan> find(String code, int version) {
        return jdbc.sql(SELECT.formatted(":v") + " where p.code = :c" + GROUP).param("c", code).param("v", version)
                .query(this::map).optional();
    }

    @Override
    public Optional<Plan> lock(String code) {
        jdbc.sql("select code from plan where code = :c for update").param("c", code).query(String.class).optional();
        return find(code);
    }

    @Override
    public void insert(PlanInput in, String actor, Instant at) {
        jdbc.sql("""
                insert into plan (code, kind, name, tagline, features, sort_order, visibility, status, limits,
                                  updated_by, updated_at, version)
                values (:code, :kind, :name, :tagline, cast(:features as jsonb), :sort, :visibility, 'DRAFT',
                        cast(:limits as jsonb), :actor, :at, 0)
                """).param("code", in.code()).param("kind", in.kind().name()).param("name", in.name().trim())
                .param("tagline", blankToNull(in.tagline())).param("features", json.write(in.features()))
                .param("sort", in.sortOrder() == null ? 0 : in.sortOrder())
                .param("visibility", (in.visibility() == null ? PlanVisibility.PUBLIC : in.visibility()).name())
                .param("limits", json.write(in.limits())).param("actor", actor).param("at", Sql.ts(at)).update();
    }

    @Override
    public void update(PlanInput in, PlanStatus status, long expectedVersion, String actor, Instant at) {
        int rows = jdbc.sql("""
                update plan set name = :name, tagline = :tagline, features = cast(:features as jsonb),
                       sort_order = :sort, visibility = :visibility, limits = cast(:limits as jsonb),
                       updated_by = :actor, updated_at = :at, version = version + 1
                where code = :code and version = :expected
                """).param("code", in.code()).param("name", in.name().trim())
                .param("tagline", blankToNull(in.tagline())).param("features", json.write(in.features()))
                .param("sort", in.sortOrder() == null ? 0 : in.sortOrder())
                .param("visibility", (in.visibility() == null ? PlanVisibility.PUBLIC : in.visibility()).name())
                .param("limits", json.write(in.limits())).param("actor", actor).param("at", Sql.ts(at))
                .param("expected", expectedVersion).update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "The plan changed; reload and retry");
        }
    }

    @Override
    public long rowVersion(String code) {
        return jdbc.sql("select version from plan where code = :c").param("c", code).query(Long.class).single();
    }

    @Override
    public void setStatus(String code, PlanStatus status, String actor, Instant at) {
        jdbc.sql("update plan set status = :s, updated_by = :a, updated_at = :at, version = version + 1 "
                + "where code = :c").param("s", status.name()).param("a", actor).param("at", Sql.ts(at))
                .param("c", code).update();
    }

    @Override
    public void setCurrentVersion(String code, int version, String actor, Instant at) {
        jdbc.sql("update plan set current_version = :v, updated_by = :a, updated_at = :at, version = version + 1 "
                + "where code = :c").param("v", version).param("a", actor).param("at", Sql.ts(at)).param("c", code)
                .update();
    }

    @Override
    public List<PlanVersion> versions(String code) {
        return jdbc.sql("select * from plan_version where plan_code = :c order by version desc").param("c", code)
                .query((rs, n) -> header(rs)).list().stream().map(this::withPrices).toList();
    }

    @Override
    public Optional<PlanVersion> version(String code, int version) {
        return jdbc.sql("select * from plan_version where plan_code = :c and version = :v").param("c", code)
                .param("v", version).query((rs, n) -> header(rs)).optional().map(this::withPrices);
    }

    @Override
    public Optional<PlanVersion> draft(String code) {
        return jdbc.sql("select * from plan_version where plan_code = :c and status = 'DRAFT'").param("c", code)
                .query((rs, n) -> header(rs)).optional().map(this::withPrices);
    }

    @Override
    public int nextVersionNumber(String code) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from plan_version where plan_code = :c")
                .param("c", code).query(Integer.class).single();
    }

    @Override
    public void insertVersion(PlanVersion v) {
        jdbc.sql("""
                insert into plan_version (plan_code, version, status, trial_days, note, created_by, created_at)
                values (:c, :v, :s, :trial, :note, :by, :at)
                """).param("c", v.planCode()).param("v", v.version()).param("s", v.status().name())
                .param("trial", v.trialDays()).param("note", blankToNull(v.note())).param("by", v.createdBy())
                .param("at", Sql.ts(v.createdAt())).update();
        writePrices(v);
    }

    @Override
    public void updateDraft(PlanVersion v) {
        jdbc.sql("update plan_version set trial_days = :trial, note = :note where plan_code = :c and version = :v "
                + "and status = 'DRAFT'").param("trial", v.trialDays()).param("note", blankToNull(v.note()))
                .param("c", v.planCode()).param("v", v.version()).update();
        jdbc.sql("delete from plan_version_price where plan_code = :c and version = :v").param("c", v.planCode())
                .param("v", v.version()).update();
        writePrices(v);
    }

    private void writePrices(PlanVersion v) {
        for (Cadence c : Cadence.values()) {
            jdbc.sql("""
                    insert into plan_version_price (plan_code, version, cadence, amount_cents)
                    values (:p, :v, :c, :amount)
                    """).param("p", v.planCode()).param("v", v.version()).param("c", c.name())
                    .param("amount", v.price(c)).update();
        }
    }

    @Override
    public void publishVersion(String code, int version, Instant at) {
        jdbc.sql("update plan_version set status = 'SUPERSEDED' where plan_code = :c and status = 'PUBLISHED'")
                .param("c", code).update();
        jdbc.sql("update plan_version set status = 'PUBLISHED', published_at = :at where plan_code = :c "
                + "and version = :v").param("at", Sql.ts(at)).param("c", code).param("v", version).update();
    }

    @Override
    public List<PriceRow> prices(String code, int version) {
        return jdbc.sql("select * from plan_version_price where plan_code = :c and version = :v order by cadence")
                .param("c", code).param("v", version).query((rs, n) -> price(rs)).list();
    }

    @Override
    public List<String> invites(String code) {
        return jdbc.sql("select org_id from plan_invite where plan_code = :c order by org_id").param("c", code)
                .query(String.class).list();
    }

    @Override
    public void setInvites(String code, List<String> orgIds, String actor, Instant at) {
        jdbc.sql("delete from plan_invite where plan_code = :c and not (org_id = any(:ids))").param("c", code)
                .param("ids", orgIds.toArray(String[]::new)).update();
        for (String org : orgIds) {
            jdbc.sql("""
                    insert into plan_invite (plan_code, org_id, created_by, created_at)
                    select :c, :o, :a, :at where exists (select 1 from organisation where id = :o)
                    on conflict do nothing
                    """).param("c", code).param("o", org).param("a", actor).param("at", Sql.ts(at)).update();
        }
    }

    @Override
    public List<String> invitedPlans(String orgId) {
        return jdbc.sql("select plan_code from plan_invite where org_id = :o").param("o", orgId).query(String.class)
                .list();
    }

    @Override
    public void recordMigration(String orgId, String planCode, int fromVersion, int toVersion, String actor,
                                String status, String error, Instant at) {
        jdbc.sql("""
                insert into plan_migration (org_id, plan_code, from_version, to_version, actor_id, status, error,
                                            migrated_at)
                values (:o, :p, :f, :t, :a, :s, :e, :at)
                on conflict (org_id, plan_code, to_version) do update set status = excluded.status,
                    error = excluded.error, actor_id = excluded.actor_id, migrated_at = excluded.migrated_at
                """).param("o", orgId).param("p", planCode).param("f", fromVersion).param("t", toVersion)
                .param("a", actor).param("s", status).param("e", error).param("at", Sql.ts(at)).update();
    }

    @Override
    public List<VersionCount> subscriberCounts() {
        return jdbc.sql("""
                select plan_code, plan_version, count(*) as n from subscription
                where status <> 'CANCELED' group by plan_code, plan_version
                """).query((rs, n) -> new VersionCount(rs.getString("plan_code"), rs.getInt("plan_version"),
                rs.getLong("n"))).list();
    }

    private Plan map(ResultSet rs, int n) throws SQLException {
        String limits = rs.getString("limits");
        return new Plan(rs.getString("code"), OrgKind.valueOf(rs.getString("kind")), rs.getString("name"),
                rs.getString("tagline"), rs.getInt("priced_version"), rs.getLong("m_cents"), rs.getLong("q_cents"),
                rs.getLong("a_cents"), rs.getInt("v_trial"), json.strings(rs.getString("features")),
                rs.getInt("sort_order"), PlanVisibility.valueOf(rs.getString("visibility")),
                PlanStatus.valueOf(rs.getString("status")),
                limits == null ? Map.of() : json.read(limits, LIMITS));
    }

    /** A version without its prices (read after the result set is closed, see {@link #withPrices}). */
    private static PlanVersion header(ResultSet rs) throws SQLException {
        return new PlanVersion(rs.getString("plan_code"), rs.getInt("version"),
                PlanVersionStatus.valueOf(rs.getString("status")), Map.of(), rs.getInt("trial_days"),
                rs.getString("note"), rs.getString("created_by"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "published_at"));
    }

    private PlanVersion withPrices(PlanVersion v) {
        Map<Cadence, Long> prices = new EnumMap<>(Cadence.class);
        for (PriceRow p : prices(v.planCode(), v.version())) {
            prices.put(p.cadence(), p.amountCents());
        }
        return new PlanVersion(v.planCode(), v.version(), v.status(), prices, v.trialDays(), v.note(), v.createdBy(),
                v.createdAt(), v.publishedAt());
    }

    private static PriceRow price(ResultSet rs) throws SQLException {
        return new PriceRow(rs.getString("plan_code"), rs.getInt("version"), Cadence.valueOf(rs.getString("cadence")),
                rs.getLong("amount_cents"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
