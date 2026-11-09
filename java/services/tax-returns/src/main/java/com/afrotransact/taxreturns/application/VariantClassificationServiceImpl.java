package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.FactOwner;
import com.afrotransact.taxreturns.domain.profile.MissingFact;
import com.afrotransact.taxreturns.domain.profile.ProfileFact;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VariantClassificationServiceImpl implements VariantClassificationService {

    private static final Logger LOG = LoggerFactory.getLogger(VariantClassificationServiceImpl.class);
    private static final String SYSTEM = "SYSTEM:catalog";
    private static final String AUTO = "SYSTEM:auto-approval";
    private static final int MIN_REASON = 3;
    private static final Pattern STRIPE_CODE = Pattern.compile("txcd_\\d{8}");

    private final VariantClassificationRepository records;
    private final DecisionEngine engine;
    private final ListingDecisions decisions;
    private final ClassificationEvents events;
    private final TaxReturnsProperties props;
    private final Access access;
    private final Clock clock;

    VariantClassificationServiceImpl(VariantClassificationRepository records, DecisionEngine engine,
                                     ListingDecisions decisions, ClassificationEvents events,
                                     TaxReturnsProperties props, Access access, Clock clock) {
        this.records = records;
        this.engine = engine;
        this.decisions = decisions;
        this.events = events;
        this.props = props;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onCatalogFactsChanged(CatalogFactsChanged e) {
        Instant now = clock.instant();
        String actor = e.changedBy() == null || e.changedBy().isBlank() ? SYSTEM : e.changedBy();
        for (Variant v : e.variants()) {
            apply(e, v, actor, now);
        }
    }

    private void apply(CatalogFactsChanged e, Variant v, String actor, Instant now) {
        String rules = engine.snapshot().ruleVersion();
        VariantClassification existing = records.find(v.atin()).orElse(null);
        if (existing == null) {
            VariantClassification fresh = new VariantClassification(v.atin(), v.gtin(), e.productId(), null,
                    e.ownerOrgId(), e.categoryCode(), e.productTypeCode(), v.facts(), Map.of(), e.sellerBehaviour(),
                    Map.of(), RecordStatus.DRAFT, null, rules, actor, now, null, null, null, 0L, now, now);
            Resolution r = resolve(fresh);
            RecordStatus status = initialStatus(fresh, r);
            VariantClassification saved = records.insert(withStatus(fresh, status, r, rules, now));
            records.addAudit(saved.atin(), new AuditEntry(now, actor, AuditAction.SUBMITTED, "Facts reported by catalog",
                    rules, null, summary(saved, r)));
            if (saved.approved()) {
                records.addAudit(saved.atin(), new AuditEntry(now, AUTO, AuditAction.APPROVED,
                        "Approved by policy " + props.tax().classification().approvalPolicy(), rules, null,
                        summary(saved, r)));
            }
            if (r != null) {
                remember(saved, r, e.ownerOrgId(), actor, now);
            }
            events.variant(saved, null, r, "Facts reported by catalog", actor, now);
            return;
        }
        boolean categoryChanged = !Objects.equals(existing.categoryCode(), e.categoryCode());
        boolean factsChanged = !existing.facts().equals(v.facts());
        boolean behaviourChanged = !existing.sellerBehaviour().equals(e.sellerBehaviour());
        if (!categoryChanged && !factsChanged && !behaviourChanged && !v.evidenceChanged()
                && Objects.equals(existing.productId(), e.productId())) {
            return;
        }
        VariantClassification updated = existing.withFacts(e.categoryCode(), e.productTypeCode(), v.facts(),
                e.sellerBehaviour(), v.gtin(), null, actor, now);
        Resolution r = resolve(updated);
        RecordStatus next;
        AuditAction action = AuditAction.FACTS_CHANGED;
        String why = "Facts changed";
        if (r == null || r.status() == ResolveStatus.MISSING_FACTS) {
            next = RecordStatus.MISSING_FACTS;
        } else if (existing.approved() && !categoryChanged && !v.evidenceChanged() && sameDecision(existing, r)) {
            next = RecordStatus.APPROVED;
        } else if (existing.approved()) {
            next = RecordStatus.PENDING_REVIEW;
            action = AuditAction.MARKED_STALE;
            why = categoryChanged ? "The category changed from " + existing.categoryCode() + " to " + e.categoryCode()
                    : v.evidenceChanged() ? "New evidence was uploaded"
                    : "The facts changed the decision from " + describe(existing.resolution()) + " to " + describe(r);
        } else {
            next = RecordStatus.PENDING_REVIEW;
            if (existing.status() != RecordStatus.PENDING_REVIEW) {
                action = AuditAction.SUBMITTED;
                why = "Facts completed or resubmitted";
            }
        }
        if (next == RecordStatus.PENDING_REVIEW && r != null && !requiresReview(updated, r)) {
            next = RecordStatus.APPROVED;
        }
        VariantClassification saved = records.update(withStatus(updated, next, r, rules, now));
        Map<String, Object> before = summary(existing, existing.resolution());
        before.put("facts", existing.facts());
        Map<String, Object> after = summary(saved, r);
        after.put("facts", saved.facts());
        records.addAudit(saved.atin(), new AuditEntry(now, actor, action, why, rules, before, after));
        if (saved.approved() && !existing.approved()) {
            records.addAudit(saved.atin(), new AuditEntry(now, AUTO, AuditAction.APPROVED,
                    "Approved by policy " + props.tax().classification().approvalPolicy(), rules, before, after));
        }
        if (r != null) {
            remember(saved, r, e.ownerOrgId(), actor, now);
        }
        events.variant(saved, existing.status().name(), r, why, actor, now);
    }

    @Override
    @Transactional(readOnly = true)
    public VariantClassificationRepository.Page list(RecordStatus status, String categoryCode, String sellerOrgId,
                                                     String q, int page, int size) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return records.list(new VariantClassificationRepository.Filter(status, categoryCode, sellerOrgId, q), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public ClassificationView get(String atin) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return view(load(atin));
    }

    @Override
    @Transactional
    public ClassificationView approve(String atin, ApproveCommand c) {
        Actor actor = access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN, TaxReturnsPermissions.TAX_CONFIGURE);
        VariantClassification rec = load(atin);
        TaxProfile profile = engine.profile(rec.categoryCode());
        Map<String, Object> staff = new LinkedHashMap<>(rec.staffFacts());
        for (Map.Entry<String, Object> f : c.staffFacts().entrySet()) {
            ProfileFact ref = profile.factsById().get(f.getKey());
            if (ref == null || ref.definition().owner() != FactOwner.STAFF) {
                throw new ReturnRuleViolation("tax.invalid-staff-fact", f.getKey() + " is not a fact that tax review sets "
                        + "for " + profile.name(), Map.of("staffFacts." + f.getKey(), "Not a staff fact"));
            }
            staff.put(f.getKey(), f.getValue());
        }
        Map<String, String> refined = new LinkedHashMap<>(rec.refinedProviderCodes());
        for (Map.Entry<String, String> p : c.refinedProviderCodes().entrySet()) {
            refined.put(provider(p.getKey()).name(), refinedCode(p.getKey(), p.getValue()));
        }
        Instant now = clock.instant();
        String by = Access.describe(actor);
        VariantClassification reviewed = rec.withReview(staff, refined, by, now, c.decisionRef());
        Resolution r = engine.resolveRecord(reviewed, reviewed.sellerBehaviour(), engine.today());
        if (!r.chargeable()) {
            throw notResolvable(r);
        }
        if (rec.approved() && rec.staffFacts().equals(staff) && rec.refinedProviderCodes().equals(refined)) {
            return view(rec);
        }
        String rules = r.ruleVersion();
        VariantClassification saved = records.update(withStatus(reviewed, RecordStatus.APPROVED, r, rules, now));
        records.addAudit(atin, new AuditEntry(now, by, AuditAction.APPROVED,
                c.reason() == null || c.reason().isBlank() ? "Approved after review" : c.reason().trim(), rules,
                summary(rec, rec.resolution()), summary(saved, r)));
        remember(saved, r, saved.ownerOrgId(), by, now);
        events.variant(saved, rec.status().name(), r, "Approved after review", by, now);
        return view(saved);
    }

    @Override
    @Transactional
    public ClassificationView reclassify(String atin, ReclassifyCommand c) {
        Actor actor = access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN, TaxReturnsPermissions.TAX_CONFIGURE);
        if (c.reason() == null || c.reason().trim().length() < MIN_REASON) {
            throw new ReturnRuleViolation("tax.reason-required", "Say why the variant moves (at least " + MIN_REASON
                    + " characters)", Map.of("reason", "Required"));
        }
        VariantClassification rec = load(atin);
        engine.profile(c.categoryCode());
        Instant now = clock.instant();
        String by = Access.describe(actor);
        String rules = engine.snapshot().ruleVersion();
        VariantClassification moved = rec.withFacts(c.categoryCode(), c.productTypeCode() == null
                ? rec.productTypeCode() : c.productTypeCode(), rec.facts(), rec.sellerBehaviour(), null, null,
                rec.classifiedBy(), rec.classifiedAt() == null ? now : rec.classifiedAt());
        Resolution r = resolve(moved);
        RecordStatus next = r == null || r.status() == ResolveStatus.MISSING_FACTS ? RecordStatus.MISSING_FACTS
                : RecordStatus.PENDING_REVIEW;
        VariantClassification saved = records.update(withStatus(moved, next, r, rules, now));
        records.addAudit(atin, new AuditEntry(now, by, AuditAction.RECLASSIFIED, c.reason().trim(), rules,
                summary(rec, rec.resolution()), summary(saved, r)));
        if (r != null) {
            remember(saved, r, saved.ownerOrgId(), by, now);
        }
        events.variant(saved, rec.status().name(), r, c.reason().trim(), by, now);
        return view(saved);
    }

    private VariantClassification load(String atin) {
        return records.find(atin).orElseThrow(() -> new NotFoundException("tax.classification-not-found",
                "No classification record for " + atin));
    }

    private ClassificationView view(VariantClassification rec) {
        Resolution r = resolve(rec);
        return new ClassificationView(rec, r, records.evidence(rec.atin()), records.audit(rec.atin()),
                records.decisions(rec.atin()));
    }

    /** The record under today's rules and the owner's behaviour; null when its category has no profile. */
    private Resolution resolve(VariantClassification rec) {
        try {
            return engine.resolveRecord(rec, rec.sellerBehaviour(), engine.today());
        } catch (NotFoundException ex) {
            LOG.warn("Variant {} reports unknown tax category {}", rec.atin(), rec.categoryCode());
            return null;
        }
    }

    private RecordStatus initialStatus(VariantClassification rec, Resolution r) {
        if (r == null || r.status() == ResolveStatus.MISSING_FACTS) {
            return RecordStatus.MISSING_FACTS;
        }
        return requiresReview(rec, r) ? RecordStatus.PENDING_REVIEW : RecordStatus.APPROVED;
    }

    private boolean requiresReview(VariantClassification rec, Resolution r) {
        return props.tax().classification().approvalPolicy().requiresReview(engine.profile(rec.categoryCode()), r);
    }

    private VariantClassification withStatus(VariantClassification rec, RecordStatus status, Resolution r, String rules,
                                             Instant now) {
        VariantClassification next = rec.with(status, r, rules, now);
        if (status == RecordStatus.APPROVED && rec.status() != RecordStatus.APPROVED && rec.reviewedBy() == null) {
            next = next.withReview(rec.staffFacts(), rec.refinedProviderCodes(), AUTO, now, null);
        }
        return next;
    }

    private void remember(VariantClassification rec, Resolution r, String orgId, String by, Instant now) {
        decisions.save(rec, rec.sellerBehaviour(), orgId, r, by, now);
    }

    private static boolean sameDecision(VariantClassification existing, Resolution r) {
        Resolution before = existing.resolution();
        return before != null && before.status() == r.status() && before.outcome() == r.outcome()
                && Objects.equals(before.internalTaxCode(), r.internalTaxCode());
    }

    private static String describe(Resolution r) {
        return r == null || r.internalTaxCode() == null ? "no decision" : r.outcome() + " " + r.internalTaxCode();
    }

    private static Map<String, Object> summary(VariantClassification rec, Resolution r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categoryCode", rec.categoryCode());
        m.put("status", rec.status().name());
        m.put("resolveStatus", r == null || r.status() == null ? null : r.status().name());
        m.put("outcome", r == null || r.outcome() == null ? null : r.outcome().name());
        m.put("internalTaxCode", r == null ? null : r.internalTaxCode());
        return m;
    }

    private static TaxProviderId provider(String name) {
        try {
            return TaxProviderId.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new ReturnRuleViolation("tax.invalid-provider", "Unknown provider " + name,
                    Map.of("refinedProviderCodes." + name, "Unknown provider"));
        }
    }

    private static String refinedCode(String provider, String code) {
        if (code == null || code.isBlank()) {
            throw new ReturnRuleViolation("tax.invalid-provider-code", "A refined code cannot be empty",
                    Map.of("refinedProviderCodes." + provider, "Required"));
        }
        if (provider(provider) == TaxProviderId.STRIPE && !STRIPE_CODE.matcher(code.trim()).matches()) {
            throw new ReturnRuleViolation("tax.invalid-provider-code", "A Stripe tax code looks like txcd_40010002",
                    Map.of("refinedProviderCodes." + provider, "Not a Stripe tax code"));
        }
        return code.trim();
    }

    private static ReturnRuleViolation notResolvable(Resolution r) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (MissingFact m : r.missingFacts()) {
            fields.put("facts." + m.factId(), m.message());
        }
        return new ReturnRuleViolation("tax.classification-not-resolvable",
                "This cannot be approved yet (" + r.status() + "): " + r.reason(), fields);
    }
}
