package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.Condition;
import com.afrotransact.taxreturns.domain.profile.DecisionRow;
import com.afrotransact.taxreturns.domain.profile.FactDefinition;
import com.afrotransact.taxreturns.domain.profile.FactKind;
import com.afrotransact.taxreturns.domain.profile.FactOwner;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProfileFact;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.StatusReason;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxProfileRepository implements TaxProfileRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTaxProfileRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<TaxProfile> all() {
        return load(null);
    }

    @Override
    public Optional<TaxProfile> find(String categoryCode) {
        return load(categoryCode).stream().findFirst();
    }

    @Override
    public Optional<Stamp> stamp(String categoryCode) {
        return jdbc.sql("select changed_by, changed_at from tax_profile where category_code = :c and version > 0")
                .param("c", categoryCode)
                .query((rs, n) -> new Stamp(rs.getString("changed_by"), Sql.instant(rs, "changed_at"))).optional();
    }

    @Override
    public String currentRuleVersion() {
        return jdbc.sql("select rule_version from tax_rule_version order by seq desc limit 1").query(String.class)
                .single();
    }

    @Override
    public String newRuleVersion(String reason, String by, Instant at) {
        jdbc.sql("select pg_advisory_xact_lock(7101)").query().singleRow();
        String base = jdbc.sql("select base_version from tax_rule_version order by seq desc limit 1")
                .query(String.class).single();
        long n = jdbc.sql("select count(*) from tax_rule_version where base_version = :b").param("b", base)
                .query(Long.class).single();
        String version = base + "+" + n;
        jdbc.sql("""
                insert into tax_rule_version (rule_version, base_version, reason, changed_by, changed_at)
                values (:v, :b, :reason, :by, :at)
                """).param("v", version).param("b", base).param("reason", reason).param("by", by)
                .param("at", Sql.ts(at)).update();
        return version;
    }

    @Override
    public boolean updateStatus(String categoryCode, ProfileStatus to, String by, Instant at) {
        return jdbc.sql("""
                update tax_profile set status = :s, changed_by = :by, changed_at = :at, version = version + 1
                where category_code = :c
                """).param("s", to.name()).param("by", by).param("at", Sql.ts(at)).param("c", categoryCode)
                .update() > 0;
    }

    @Override
    public void audit(AuditRow r) {
        jdbc.sql("""
                insert into tax_profile_audit (kind, category_code, internal_tax_code, provider, at, actor, reason,
                    approved_by, decision_ref, rule_version, before_state, after_state)
                values (:kind, :category, :code, :provider, :at, :actor, :reason, :approvedBy, :ref, :rules,
                    cast(:before as jsonb), cast(:after as jsonb))
                """)
                .param("kind", r.kind()).param("category", r.categoryCode()).param("code", r.internalTaxCode())
                .param("provider", r.provider()).param("at", Sql.ts(r.at())).param("actor", r.actor())
                .param("reason", r.reason()).param("approvedBy", r.approvedBy()).param("ref", r.decisionRef())
                .param("rules", r.ruleVersion()).param("before", json.write(r.before()))
                .param("after", json.write(r.after())).update();
    }

    @Override
    public List<AuditRow> auditOf(String categoryCode, int limit) {
        return jdbc.sql("select * from tax_profile_audit where category_code = :c order by at desc, id desc limit :n")
                .param("c", categoryCode).param("n", limit)
                .query((rs, n) -> new AuditRow(rs.getString("kind"), rs.getString("category_code"),
                        rs.getString("internal_tax_code"), rs.getString("provider"), Sql.instant(rs, "at"),
                        rs.getString("actor"), rs.getString("reason"), rs.getString("approved_by"),
                        rs.getString("decision_ref"), rs.getString("rule_version"),
                        json.readMap(rs.getString("before_state")), json.readMap(rs.getString("after_state"))))
                .list();
    }

    private List<TaxProfile> load(String only) {
        Map<String, FactDefinition> defs = new HashMap<>();
        jdbc.sql("select * from tax_fact_definition").query((rs, n) -> definition(rs)).list()
                .forEach(d -> defs.put(d.id(), d));
        Map<String, List<ProfileFact>> facts = new HashMap<>();
        String filter = only == null ? "" : " where category_code = :c";
        JdbcClient.StatementSpec factQuery = jdbc.sql("select * from tax_profile_fact" + filter
                + " order by category_code, position");
        if (only != null) {
            factQuery = factQuery.param("c", only);
        }
        factQuery.query((rs, n) -> Map.entry(rs.getString("category_code"), new ProfileFact(
                defs.get(rs.getString("fact_id")), rs.getBoolean("required"),
                Condition.parse(json.readTree(rs.getString("required_when"))), json.strings(rs.getString("choices")))))
                .list().forEach(e -> facts.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        Map<String, Map<String, String>> codes = new HashMap<>();
        jdbc.sql("select internal_tax_code, provider, provider_code from provider_code_map "
                + "where provider_code is not null and active")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).list()
                .forEach(a -> codes.computeIfAbsent(a[0], k -> new LinkedHashMap<>()).put(a[1], a[2]));
        Map<String, List<DecisionRow>> rows = new HashMap<>();
        JdbcClient.StatementSpec rowQuery = jdbc.sql("""
                select r.id, r.category_code, r.scenario, r.when_expr, r.internal_tax_code, o.outcome, o.description,
                       o.rate_display, o.note
                from tax_decision_row r join tax_outcome_code o on o.internal_tax_code = r.internal_tax_code
                """ + (only == null ? "" : " where r.category_code = :c") + " order by r.category_code, r.position");
        if (only != null) {
            rowQuery = rowQuery.param("c", only);
        }
        rowQuery.query((rs, n) -> Map.entry(rs.getString("category_code"), new DecisionRow(rs.getString("id"),
                rs.getInt("scenario"), Condition.parse(json.readTree(rs.getString("when_expr"))),
                rs.getString("description"), Outcome.valueOf(rs.getString("outcome")),
                rs.getString("internal_tax_code"), codes.getOrDefault(rs.getString("internal_tax_code"), Map.of()),
                rs.getString("rate_display"), rs.getString("note")))).list()
                .forEach(e -> rows.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        JdbcClient.StatementSpec profileQuery = jdbc.sql("select * from tax_profile"
                + (only == null ? "" : " where category_code = :c") + " order by department_position, position");
        if (only != null) {
            profileQuery = profileQuery.param("c", only);
        }
        return profileQuery.query((rs, n) -> profile(rs, facts, rows)).list();
    }

    private TaxProfile profile(ResultSet rs, Map<String, List<ProfileFact>> facts, Map<String, List<DecisionRow>> rows)
            throws SQLException {
        String code = rs.getString("category_code");
        List<StatusReason> reasons = new ArrayList<>();
        Object tree = json.readTree(rs.getString("status_reasons"));
        if (tree instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object decision = m.get("decision");
                    reasons.add(new StatusReason(ProfileStatus.valueOf((String) m.get("status")),
                            decision instanceof Number d ? d.intValue() : 0, (String) m.get("text")));
                }
            }
        }
        return new TaxProfile(code, rs.getString("name"), rs.getString("department_code"),
                rs.getString("department_name"), ProfileStatus.valueOf(rs.getString("status")), reasons,
                rs.getString("default_outcome"), json.strings(rs.getString("stripe_codes")),
                json.strings(rs.getString("conflicting_codes")), json.strings(rs.getString("fact_groups")),
                facts.getOrDefault(code, List.of()), rows.getOrDefault(code, List.of()), rs.getString("note"));
    }

    private FactDefinition definition(ResultSet rs) throws SQLException {
        List<FactDefinition.Choice> choices = new ArrayList<>();
        if (json.readTree(rs.getString("choices")) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    choices.add(new FactDefinition.Choice((String) m.get("value"), (String) m.get("label")));
                }
            }
        }
        return new FactDefinition(rs.getString("id"), rs.getString("label"), FactKind.valueOf(rs.getString("kind")),
                rs.getString("unit"), FactOwner.valueOf(rs.getString("owner")), rs.getString("help"),
                rs.getBoolean("evidence"), choices);
    }
}
