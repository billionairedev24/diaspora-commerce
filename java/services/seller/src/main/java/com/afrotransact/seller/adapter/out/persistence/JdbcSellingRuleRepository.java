package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.SellingDocumentRequirement;
import com.afrotransact.seller.domain.SellingLevel;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingRuleRepository;
import tools.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellingRuleRepository implements SellingRuleRepository {

    private static final TypeReference<List<SellingDocumentRequirement>> DOCS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellingRuleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<SellingRule> findActive(Collection<SellingScope> scopes) {
        if (scopes.isEmpty()) {
            return List.of();
        }
        List<String> keys = scopes.stream().map(s -> s.type().name() + ":" + s.key()).toList();
        return jdbc.sql("select * from selling_rule where active and (scope_type || ':' || scope_key) in (:keys)")
                .param("keys", keys).query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<SellingRule> findGated() {
        return jdbc.sql("select * from selling_rule where active and level <> 'SELL' "
                + "order by scope_type, scope_label").query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<String> categoriesWithCapability(String capability) {
        return jdbc.sql("select scope_key from selling_rule where scope_type = 'CATEGORY' and capability = :c and active "
                + "order by scope_key").param("c", capability).query(String.class).list();
    }

    @Override
    public Optional<SellingRule> find(UUID id) {
        return jdbc.sql("select * from selling_rule where id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<SellingRule> find(SellingScope scope) {
        return jdbc.sql("select * from selling_rule where scope_type = :t and scope_key = :k")
                .param("t", scope.type().name()).param("k", scope.key()).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Page<SellingRule> search(RuleSearch q) {
        List<String> where = new ArrayList<>(List.of("true"));
        Map<String, Object> params = new HashMap<>();
        if (q.type() != null) {
            where.add("scope_type = :type");
            params.put("type", q.type().name());
        }
        if (q.level() != null) {
            where.add("level = :level");
            params.put("level", q.level().name());
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.add("(lower(scope_label) like :text or scope_key like :text)");
            params.put("text", "%" + q.text().trim().toLowerCase().replace("%", "") + "%");
        }
        if (q.activeOnly()) {
            where.add("active");
        }
        String from = " from selling_rule where " + String.join(" and ", where);
        JdbcClient.StatementSpec count = jdbc.sql("select count(*)" + from);
        JdbcClient.StatementSpec rows = jdbc.sql("select *" + from
                + " order by scope_type, scope_label limit :limit offset :offset")
                .param("limit", q.size()).param("offset", q.page() * q.size());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            rows = rows.param(e.getKey(), e.getValue());
        }
        return new Page<>(rows.query((rs, n) -> map(rs)).list(), q.page(), q.size(),
                count.query(Long.class).single());
    }

    @Override
    public SellingRule insert(SellingRule r) {
        jdbc.sql("""
                insert into selling_rule (id, scope_type, scope_key, scope_label, level, required_documents, reason,
                    note, approval_level, capability, condition_materials, condition_watch, reapply_after_days,
                    active, source, updated_at, updated_by_name, version)
                values (:id, :type, :key, :label, :level, cast(:docs as jsonb), :reason, :note, :approvalLevel,
                    :capability, cast(:materials as jsonb), :watch, :reapply, :active, :source, :at, :by, 0)
                """)
                .param("id", r.id()).param("type", r.scope().type().name()).param("key", r.scope().key())
                .param("label", r.label()).param("level", r.level().name()).param("docs", json.write(r.documents()))
                .param("reason", nz(r.reason())).param("note", nz(r.note())).param("approvalLevel", r.approvalLevel())
                .param("capability", r.capability())
                .param("materials", json.write(r.materials().stream().sorted().toList()))
                .param("watch", r.watch()).param("reapply", r.reapplyAfterDays()).param("active", r.active())
                .param("source", r.source()).param("at", Sql.ts(r.updatedAt())).param("by", r.updatedByName())
                .update();
        return find(r.id()).orElseThrow();
    }

    @Override
    public SellingRule update(SellingRule r) {
        int rows = jdbc.sql("""
                update selling_rule set scope_label = :label, level = :level, required_documents = cast(:docs as jsonb),
                    reason = :reason, note = :note, capability = :capability,
                    condition_materials = cast(:materials as jsonb), condition_watch = :watch,
                    reapply_after_days = :reapply, active = :active, updated_at = :at, updated_by_name = :by,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("label", r.label()).param("level", r.level().name()).param("docs", json.write(r.documents()))
                .param("reason", nz(r.reason())).param("note", nz(r.note())).param("capability", r.capability())
                .param("materials", json.write(r.materials().stream().sorted().toList()))
                .param("watch", r.watch()).param("reapply", r.reapplyAfterDays()).param("active", r.active())
                .param("at", Sql.ts(r.updatedAt())).param("by", r.updatedByName()).param("id", r.id())
                .param("version", r.version()).update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "This rule changed since you opened it; reload it");
        }
        return find(r.id()).orElseThrow();
    }

    @Override
    public void audit(RuleAudit a) {
        jdbc.sql("""
                insert into selling_rule_audit (id, rule_id, action, actor_id, actor_name, reason, before_json,
                    after_json, changed_at)
                values (:id, :rule, :action, :actor, :name, :reason, cast(:before as jsonb), cast(:after as jsonb), :at)
                """)
                .param("id", a.id()).param("rule", a.ruleId()).param("action", a.action())
                .param("actor", a.actorId()).param("name", a.actorName()).param("reason", a.reason())
                .param("before", a.before() == null ? null : json.write(a.before())).param("after", json.write(a.after())).param("at", Sql.ts(a.changedAt()))
                .update();
    }

    @Override
    public List<RuleAudit> audits(UUID ruleId) {
        return jdbc.sql("select * from selling_rule_audit where rule_id = :r order by changed_at desc")
                .param("r", ruleId).query((rs, n) -> new RuleAudit(rs.getObject("id", UUID.class),
                        rs.getObject("rule_id", UUID.class), rs.getString("action"), rs.getString("actor_id"),
                        rs.getString("actor_name"), rs.getString("reason"), rs.getString("before_json"),
                        rs.getString("after_json"), Sql.instant(rs, "changed_at"))).list();
    }

    private SellingRule map(ResultSet rs) throws SQLException {
        return new SellingRule(rs.getObject("id", UUID.class),
                new SellingScope(SellingScopeType.valueOf(rs.getString("scope_type")), rs.getString("scope_key")),
                rs.getString("scope_label"), SellingLevel.valueOf(rs.getString("level")),
                json.read(rs.getString("required_documents"), DOCS), rs.getString("reason"), rs.getString("note"),
                rs.getString("approval_level"), rs.getString("capability"),
                Set.copyOf(json.strings(rs.getString("condition_materials"))), rs.getBoolean("condition_watch"),
                rs.getInt("reapply_after_days"), rs.getBoolean("active"), rs.getString("source"),
                Sql.instant(rs, "updated_at"), rs.getString("updated_by_name"), rs.getLong("version"));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
