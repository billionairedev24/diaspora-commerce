package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.InformationRequest;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.MissingFact;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ReviewReason;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcVariantClassificationRepository implements VariantClassificationRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcVariantClassificationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<VariantClassification> find(String atin) {
        return jdbc.sql("select * from variant_classification where atin = :a").param("a", atin)
                .query((rs, n) -> record(rs)).optional();
    }

    @Override
    public Map<String, VariantClassification> findAll(Collection<String> atins) {
        Map<String, VariantClassification> out = new LinkedHashMap<>();
        if (atins.isEmpty()) {
            return out;
        }
        jdbc.sql("select * from variant_classification where atin in (:atins)").param("atins", atins)
                .query((rs, n) -> record(rs)).list().forEach(v -> out.put(v.atin(), v));
        return out;
    }

    @Override
    public VariantClassification insert(VariantClassification v) {
        jdbc.sql("""
                insert into variant_classification (atin, gtin, product_id, title, owner_org_id, category_code,
                    product_type_code, facts, staff_facts, seller_behaviour, refined_provider_codes, status, resolution,
                    rule_version, classified_by, classified_at, reviewed_by, reviewed_at, decision_ref, info_request, info_requested_by,
                    info_requested_at, audit_sampled_at, version, created_at, updated_at)
                values (:atin, :gtin, :product, :title, :owner, :category, :type, cast(:facts as jsonb),
                    cast(:staff as jsonb), cast(:behaviour as jsonb), cast(:refined as jsonb), :status,
                    cast(:resolution as jsonb), :rules, :classifiedBy, :classifiedAt, :reviewedBy, :reviewedAt, :ref, :infoRequest, :infoBy, :infoAt, :auditAt, 0,
                    :created, :updated)
                """)
                .param("atin", v.atin()).param("gtin", v.gtin()).param("product", v.productId())
                .param("title", v.title()).param("owner", v.ownerOrgId()).param("category", v.categoryCode())
                .param("type", v.productTypeCode()).param("facts", json.write(v.facts()))
                .param("staff", json.write(v.staffFacts())).param("behaviour", json.write(v.sellerBehaviour()))
                .param("refined", json.write(v.refinedProviderCodes())).param("status", v.status().name())
                .param("resolution", json.write(v.resolution())).param("rules", v.ruleVersion())
                .param("classifiedBy", v.classifiedBy()).param("classifiedAt", Sql.ts(v.classifiedAt()))
                .param("reviewedBy", v.reviewedBy()).param("reviewedAt", Sql.ts(v.reviewedAt()))
                .param("ref", v.decisionRef()).param("infoRequest", info(v, 0)).param("infoBy", info(v, 1))
                .param("infoAt", Sql.ts(v.informationRequest() == null ? null : v.informationRequest().requestedAt()))
                .param("auditAt", Sql.ts(v.auditSampledAt())).param("created", Sql.ts(v.createdAt()))
                .param("updated", Sql.ts(v.updatedAt())).update();
        return find(v.atin()).orElseThrow();
    }

    @Override
    public VariantClassification update(VariantClassification v) {
        int rows = jdbc.sql("""
                update variant_classification set gtin = :gtin, product_id = :product, title = :title,
                    category_code = :category, product_type_code = :type, facts = cast(:facts as jsonb),
                    staff_facts = cast(:staff as jsonb), seller_behaviour = cast(:behaviour as jsonb),
                    refined_provider_codes = cast(:refined as jsonb), status = :status,
                    resolution = cast(:resolution as jsonb), rule_version = :rules, classified_by = :classifiedBy,
                    classified_at = :classifiedAt, reviewed_by = :reviewedBy, reviewed_at = :reviewedAt,
                    decision_ref = :ref, info_request = :infoRequest, info_requested_by = :infoBy,
                    info_requested_at = :infoAt, audit_sampled_at = :auditAt, updated_at = :updated, version = version + 1
                where atin = :atin and version = :version
                """)
                .param("atin", v.atin()).param("version", v.version()).param("gtin", v.gtin())
                .param("product", v.productId()).param("title", v.title()).param("category", v.categoryCode())
                .param("type", v.productTypeCode()).param("facts", json.write(v.facts()))
                .param("staff", json.write(v.staffFacts())).param("behaviour", json.write(v.sellerBehaviour()))
                .param("refined", json.write(v.refinedProviderCodes())).param("status", v.status().name())
                .param("resolution", json.write(v.resolution())).param("rules", v.ruleVersion())
                .param("classifiedBy", v.classifiedBy()).param("classifiedAt", Sql.ts(v.classifiedAt()))
                .param("reviewedBy", v.reviewedBy()).param("reviewedAt", Sql.ts(v.reviewedAt()))
                .param("ref", v.decisionRef()).param("infoRequest", info(v, 0)).param("infoBy", info(v, 1))
                .param("infoAt", Sql.ts(v.informationRequest() == null ? null : v.informationRequest().requestedAt()))
                .param("auditAt", Sql.ts(v.auditSampledAt())).param("updated", Sql.ts(v.updatedAt())).update();
        if (rows == 0) {
            throw new StaleRecordException(v.atin());
        }
        return find(v.atin()).orElseThrow();
    }

    @Override
    public Page list(Filter f, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.status() != null) {
            where.append(" and v.status = :status");
            params.put("status", f.status().name());
        }
        if (f.auditOnly()) {
            where.append(" and v.audit_sampled_at is not null and v.status = 'APPROVED'");
        }
        if (f.categoryCode() != null && !f.categoryCode().isBlank()) {
            where.append(" and v.category_code = :category");
            params.put("category", f.categoryCode());
        }
        if (f.sellerOrgId() != null && !f.sellerOrgId().isBlank()) {
            where.append(" and (v.owner_org_id = :org or exists (select 1 from listing_tax_decision d "
                    + "where d.atin = v.atin and d.seller_org_ids @> cast(:orgJson as jsonb)))");
            params.put("org", f.sellerOrgId());
            params.put("orgJson", json.write(List.of(f.sellerOrgId())));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String sellers = f.qSellerOrgIds().isEmpty() ? "" : " or v.owner_org_id in (:qOrgs) or exists (select 1 from "
                    + "listing_tax_decision d, jsonb_array_elements_text(d.seller_org_ids) as o(org) "
                    + "where d.atin = v.atin and o.org in (:qOrgs))";
            where.append(" and (v.atin ilike :q or v.gtin ilike :q or v.title ilike :q").append(sellers).append(")");
            params.put("q", "%" + f.q().trim().replace("%", "") + "%");
            if (!f.qSellerOrgIds().isEmpty()) {
                params.put("qOrgs", f.qSellerOrgIds());
            }
        }
        if (f.reason() != null) {
            where.append(" and ").append(reasonSql(f.reason()));
        }
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from variant_classification v" + where);
        JdbcClient.StatementSpec rows = jdbc.sql("select v.* from variant_classification v" + where + orderBy(f)
                + " limit :limit offset :offset");
        for (Map.Entry<String, Object> p : params.entrySet()) {
            count = count.param(p.getKey(), p.getValue());
            rows = rows.param(p.getKey(), p.getValue());
        }
        long total = count.query(Long.class).single();
        List<VariantClassification> items = rows.param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> record(rs)).list();
        return new Page(items, total);
    }

    private static final String WAITING = "v.status in ('PENDING_REVIEW', 'MISSING_FACTS', 'DRAFT')";
    private static final String NO_MISSING = "jsonb_array_length(coalesce(v.resolution -> 'missingFacts', '[]'::jsonb)) = 0";

    /** The records {@link ReviewReason#of} gives this reason; keep in step with it. */
    private static String reasonSql(ReviewReason reason) {
        return switch (reason) {
            case AUDIT_SAMPLE -> "(v.status = 'APPROVED' and v.audit_sampled_at is not null)";
            case CHANGED -> "v.status = 'STALE'";
            case WITH_SELLER -> "v.status = 'REJECTED'";
            case CONFLICT -> resolutionIs("CONFLICT_OPEN");
            case REGULATED_CATEGORY -> resolutionIs("NEEDS_APPROVAL");
            case CODE_TO_VERIFY -> resolutionIs("REFINE_CODE");
            case BLOCKED -> resolutionIs("BLOCKED");
            case MISSING_FACT -> "(" + WAITING + " and v.resolution ->> 'status' = 'MISSING_FACTS' and not (" + NO_MISSING
                    + "))";
            case NO_MATCHING_RULE -> "(" + WAITING + " and (v.resolution is null or v.resolution = 'null'::jsonb or "
                    + "(v.resolution ->> 'status' = 'MISSING_FACTS' and " + NO_MISSING + ")))";
            case NEW_LISTING -> "(" + WAITING + " and v.resolution ->> 'status' = 'RESOLVED')";
        };
    }

    private static String resolutionIs(String status) {
        return "(" + WAITING + " and v.resolution ->> 'status' = '" + status + "')";
    }

    /** Sortable columns by name (never a client-supplied identifier); ties and the default keep the queue order. */
    private static String orderBy(Filter f) {
        String column = f.sort() == null ? null : switch (f.sort()) {
            case "title" -> "lower(v.title)";
            case "atin" -> "v.atin";
            case "category" -> "v.category_code";
            case "status" -> "v.status";
            case "submitted" -> "v.classified_at";
            case "reviewed" -> "v.reviewed_at";
            default -> null;
        };
        if (column != null) {
            return " order by " + column + (f.descending() ? " desc" : " asc") + " nulls last, v.atin";
        }
        return f.status() == RecordStatus.PENDING_REVIEW || f.auditOnly() ? " order by v.updated_at asc, v.atin"
                : " order by v.updated_at desc, v.atin";
    }

    @Override
    public Map<String, List<String>> sellerOrgIds(Collection<String> atins) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (atins.isEmpty()) {
            return out;
        }
        jdbc.sql("select atin, owner_org_id from variant_classification where atin in (:atins)").param("atins", atins)
                .query((rs, n) -> Map.entry(rs.getString("atin"), rs.getString("owner_org_id") == null ? "" : rs.getString("owner_org_id")))
                .list().forEach(e -> add(out, e.getKey(), e.getValue()));
        jdbc.sql("select d.atin, o.org from listing_tax_decision d, jsonb_array_elements_text(d.seller_org_ids) as o(org) "
                + "where d.atin in (:atins) order by d.decided_at desc").param("atins", atins)
                .query((rs, n) -> Map.entry(rs.getString("atin"), rs.getString("org"))).list()
                .forEach(e -> add(out, e.getKey(), e.getValue()));
        return out;
    }

    private static void add(Map<String, List<String>> out, String atin, String org) {
        List<String> orgs = out.computeIfAbsent(atin, k -> new ArrayList<>());
        if (!org.isBlank() && !orgs.contains(org)) {
            orgs.add(org);
        }
    }

    @Override
    public List<String> atinsWithStatus(RecordStatus status) {
        return jdbc.sql("select atin from variant_classification where status = :s order by updated_at, atin")
                .param("s", status.name()).query(String.class).list();
    }

    @Override
    public void addAudit(String atin, AuditEntry e) {
        jdbc.sql("""
                insert into variant_classification_audit (atin, at, actor, action, reason, rule_version, before_state,
                    after_state)
                values (:atin, :at, :actor, :action, :reason, :rules, cast(:before as jsonb), cast(:after as jsonb))
                """)
                .param("atin", atin).param("at", Sql.ts(e.at())).param("actor", e.actor())
                .param("action", e.action().name()).param("reason", e.reason()).param("rules", e.ruleVersion())
                .param("before", json.write(e.before())).param("after", json.write(e.after())).update();
    }

    @Override
    public List<AuditEntry> audit(String atin) {
        return jdbc.sql("select * from variant_classification_audit where atin = :a order by at, id").param("a", atin)
                .query((rs, n) -> new AuditEntry(Sql.instant(rs, "at"), rs.getString("actor"),
                        AuditAction.valueOf(rs.getString("action")), rs.getString("reason"),
                        rs.getString("rule_version"), json.readMap(rs.getString("before_state")),
                        json.readMap(rs.getString("after_state")))).list();
    }

    @Override
    public void addEvidence(String atin, Evidence e) {
        jdbc.sql("""
                insert into variant_classification_evidence (atin, fact_id, file_name, url, uploaded_by, uploaded_at)
                values (:atin, :fact, :file, :url, :by, :at)
                """)
                .param("atin", atin).param("fact", e.factId()).param("file", e.fileName()).param("url", e.url())
                .param("by", e.uploadedBy()).param("at", Sql.ts(e.uploadedAt())).update();
    }

    @Override
    public List<Evidence> evidence(String atin) {
        return jdbc.sql("select * from variant_classification_evidence where atin = :a order by uploaded_at, id")
                .param("a", atin).query((rs, n) -> new Evidence(rs.getString("fact_id"), rs.getString("file_name"),
                        rs.getString("url"), rs.getString("uploaded_by"), Sql.instant(rs, "uploaded_at"))).list();
    }

    @Override
    public Optional<ListingDecision> decision(String atin, String behaviourHash) {
        return jdbc.sql("select * from listing_tax_decision where atin = :a and behaviour_hash = :h")
                .param("a", atin).param("h", behaviourHash).query((rs, n) -> decision(rs)).optional();
    }

    @Override
    public List<ListingDecision> decisions(String atin) {
        return jdbc.sql("select * from listing_tax_decision where atin = :a order by decided_at desc")
                .param("a", atin).query((rs, n) -> decision(rs)).list();
    }

    @Override
    public void saveDecision(ListingDecision d) {
        jdbc.sql("""
                insert into listing_tax_decision (atin, behaviour_hash, seller_behaviour, seller_org_ids, category_code,
                    facts_snapshot, status, outcome, internal_tax_code, provider_codes, rule_version, reason,
                    missing_facts, decided_at, decided_by, approved_by)
                values (:atin, :hash, cast(:behaviour as jsonb), cast(:orgs as jsonb), :category,
                    cast(:facts as jsonb), :status, :outcome, :code, cast(:codes as jsonb), :rules, :reason,
                    cast(:missing as jsonb), :at, :by, :approvedBy)
                on conflict (atin, behaviour_hash) do update set seller_behaviour = excluded.seller_behaviour,
                    seller_org_ids = excluded.seller_org_ids, category_code = excluded.category_code,
                    facts_snapshot = excluded.facts_snapshot, status = excluded.status, outcome = excluded.outcome,
                    internal_tax_code = excluded.internal_tax_code, provider_codes = excluded.provider_codes,
                    rule_version = excluded.rule_version, reason = excluded.reason,
                    missing_facts = excluded.missing_facts, decided_at = excluded.decided_at,
                    decided_by = excluded.decided_by, approved_by = excluded.approved_by
                """)
                .param("atin", d.atin()).param("hash", d.behaviourHash()).param("behaviour", json.write(d.sellerBehaviour()))
                .param("orgs", json.write(d.sellerOrgIds())).param("category", d.categoryCode())
                .param("facts", json.write(d.factsSnapshot())).param("status", d.status().name())
                .param("outcome", d.outcome() == null ? null : d.outcome().name()).param("code", d.internalTaxCode())
                .param("codes", json.write(d.providerCodes())).param("rules", d.ruleVersion())
                .param("reason", d.reason()).param("missing", json.write(d.missingFacts()))
                .param("at", Sql.ts(d.decidedAt())).param("by", d.decidedBy()).param("approvedBy", d.approvedBy())
                .update();
    }

    private ListingDecision decision(ResultSet rs) throws SQLException {
        List<MissingFact> missing = new ArrayList<>();
        if (json.readTree(rs.getString("missing_facts")) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    missing.add(new MissingFact((String) m.get("factId"), (String) m.get("label"),
                            (String) m.get("message")));
                }
            }
        }
        String outcome = rs.getString("outcome");
        return new ListingDecision(rs.getString("atin"), rs.getString("behaviour_hash"),
                json.readMap(rs.getString("seller_behaviour")), json.strings(rs.getString("seller_org_ids")),
                rs.getString("category_code"), json.readMap(rs.getString("facts_snapshot")),
                ResolveStatus.valueOf(rs.getString("status")), outcome == null ? null : Outcome.valueOf(outcome),
                rs.getString("internal_tax_code"), json.readStringMap(rs.getString("provider_codes")),
                rs.getString("rule_version"), rs.getString("reason"), missing, Sql.instant(rs, "decided_at"),
                rs.getString("decided_by"), rs.getString("approved_by"));
    }

    @Override
    public long ordersAffected(String atin) {
        return jdbc.sql("select count(distinct order_id) from order_line_snapshot where variant_atin = :a")
                .param("a", atin).query(Long.class).single();
    }

    private static String info(VariantClassification v, int field) {
        InformationRequest r = v.informationRequest();
        return r == null ? null : field == 0 ? r.message() : r.requestedBy();
    }

    private VariantClassification record(ResultSet rs) throws SQLException {
        String productId = rs.getString("product_id");
        return new VariantClassification(rs.getString("atin"), rs.getString("gtin"),
                productId == null ? null : UUID.fromString(productId), rs.getString("title"),
                rs.getString("owner_org_id"), rs.getString("category_code"), rs.getString("product_type_code"),
                json.readMap(rs.getString("facts")), json.readMap(rs.getString("staff_facts")),
                json.readMap(rs.getString("seller_behaviour")), json.readStringMap(rs.getString("refined_provider_codes")),
                RecordStatus.valueOf(rs.getString("status")), json.read(rs.getString("resolution"), Resolution.class),
                rs.getString("rule_version"), rs.getString("classified_by"), Sql.instant(rs, "classified_at"),
                rs.getString("reviewed_by"), Sql.instant(rs, "reviewed_at"), rs.getString("decision_ref"),
                rs.getString("info_request") == null ? null : new InformationRequest(rs.getString("info_request"),
                        rs.getString("info_requested_by"), Sql.instant(rs, "info_requested_at")),
                Sql.instant(rs, "audit_sampled_at"), rs.getLong("version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }
}
