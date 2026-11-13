package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.FactOwner;
import com.afrotransact.taxreturns.domain.profile.InformationRequest;
import com.afrotransact.taxreturns.domain.profile.MissingFact;
import com.afrotransact.taxreturns.domain.profile.ProfileFact;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.RiskReviewDecision;
import com.afrotransact.taxreturns.domain.profile.RiskReviewPolicy;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.TaxReviewSettingsRepository;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class VariantClassificationServiceImpl implements VariantClassificationService {

    private static final Logger LOG = LoggerFactory.getLogger(VariantClassificationServiceImpl.class);
    private static final String SYSTEM = "SYSTEM:catalog";
    private static final String AUTO = "SYSTEM:auto-approval";
    /** Who classified and approved an item under the risk-based rule. */
    static final String AUTO_RISK = "auto";
    private static final int MIN_REASON = 3;
    private static final int MIN_MESSAGE = 10;
    private static final int MAX_MESSAGE = 1000;
    private static final Pattern STRIPE_CODE = Pattern.compile("txcd_\\d{8}");

    private final VariantClassificationRepository records;
    private final DecisionEngine engine;
    private final ListingDecisions decisions;
    private final ClassificationEvents events;
    private final TaxReturnsProperties props;
    private final TaxReviewSettingsRepository reviewSettings;
    private final Access access;
    private final Clock clock;

    VariantClassificationServiceImpl(VariantClassificationRepository records, DecisionEngine engine,
                                     ListingDecisions decisions, ClassificationEvents events,
                                     TaxReturnsProperties props, TaxReviewSettingsRepository reviewSettings,
                                     Access access, Clock clock) {
        this.records = records;
        this.engine = engine;
        this.decisions = decisions;
        this.events = events;
        this.props = props;
        this.reviewSettings = reviewSettings;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onCatalogFactsChanged(CatalogFactsChanged e) {
        Instant now = clock.instant();
        String actor = e.changedBy() == null || e.changedBy().isBlank() ? SYSTEM : e.changedBy();
        ReviewSettings review = reviewSettings.current();
        for (Variant v : e.variants()) {
            apply(e, v, actor, now, review);
        }
    }

    private void apply(CatalogFactsChanged e, Variant v, String actor, Instant now, ReviewSettings review) {
        String rules = engine.snapshot().ruleVersion();
        VariantClassification existing = records.find(v.atin()).orElse(null);
        if (existing == null) {
            VariantClassification fresh = new VariantClassification(v.atin(), v.gtin(), e.productId(), e.title(),
                    e.ownerOrgId(), e.categoryCode(), e.productTypeCode(), v.facts(), Map.of(), e.sellerBehaviour(),
                    Map.of(), RecordStatus.DRAFT, null, rules, actor, now, null, null, null, 0L, now, now);
            Resolution r = resolve(fresh);
            Review rv = r == null || r.status() == ResolveStatus.MISSING_FACTS ? null : review(fresh, r, review);
            RecordStatus status = rv == null ? RecordStatus.MISSING_FACTS
                    : rv.needsReview() ? RecordStatus.PENDING_REVIEW : RecordStatus.APPROVED;
            VariantClassification saved = records.insert(markAutoApproved(withStatus(fresh, status, r, rules, now), rv, now));
            records.addAudit(saved.atin(), new AuditEntry(now, actor, AuditAction.SUBMITTED, "Facts reported by catalog",
                    rules, null, summary(saved, r)));
            if (saved.approved()) {
                auditAutoApproval(saved, rv, r, rules, null, now);
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
            // Nothing to decide again; a title the queue has not seen yet is just remembered (staff read names, not ids).
            if (e.title() != null && !e.title().isBlank() && !e.title().equals(existing.title())) {
                records.update(existing.withTitle(e.title()));
            }
            return;
        }
        VariantClassification updated = existing.withFacts(e.categoryCode(), e.productTypeCode(), v.facts(),
                e.sellerBehaviour(), v.gtin(), e.title(), actor, now);
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
        Review rv = null;
        if (next == RecordStatus.PENDING_REVIEW && r != null) {
            rv = review(updated, r, review);
            if (!rv.needsReview()) {
                next = RecordStatus.APPROVED;
            }
        }
        VariantClassification saved = records.update(markAutoApproved(withStatus(updated, next, r, rules, now), rv, now));
        Map<String, Object> before = summary(existing, existing.resolution());
        before.put("facts", existing.facts());
        Map<String, Object> after = summary(saved, r);
        after.put("facts", saved.facts());
        records.addAudit(saved.atin(), new AuditEntry(now, actor, action, why, rules, before, after));
        if (saved.approved() && rv != null && !rv.needsReview()) {
            auditAutoApproval(saved, rv, r, rules, before, now);
        }
        if (r != null) {
            remember(saved, r, e.ownerOrgId(), actor, now);
        }
        events.variant(saved, existing.status().name(), r, why, actor, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoApproveIfQualified(String atin) {
        VariantClassification rec = records.find(atin).orElse(null);
        ReviewSettings review = reviewSettings.current();
        if (rec == null || rec.status() != RecordStatus.PENDING_REVIEW || rec.informationRequest() != null
                || !review.riskBased() || movedByStaff(atin)) {
            return false;
        }
        Resolution r = resolve(rec);
        if (r == null || r.status() != ResolveStatus.RESOLVED) {
            return false;
        }
        Review rv = review(rec, r, review);
        if (rv.needsReview()) {
            return false;
        }
        Instant now = clock.instant();
        String rules = r.ruleVersion();
        Map<String, Object> before = summary(rec, rec.resolution());
        VariantClassification saved = records.update(markAutoApproved(withStatus(rec, RecordStatus.APPROVED, r, rules, now),
                rv, now));
        String why = "Approved by the risk-based rule after waiting in review. " + rv.rule();
        auditAutoApproval(saved, new Review(false, why, true, rv.sampled(), rv.auditPercent()), r, rules, before, now);
        remember(saved, r, saved.ownerOrgId(), AUTO_RISK, now);
        events.variant(saved, rec.status().name(), r, why, AUTO_RISK, now);
        return true;
    }

    /** True when the latest thing that happened to the record was a staff reclassification: a person must confirm it. */
    private boolean movedByStaff(String atin) {
        List<AuditEntry> trail = records.audit(atin);
        return !trail.isEmpty() && trail.getLast().action() == AuditAction.RECLASSIFIED;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<String> pendingReviewAtins() {
        return records.atinsWithStatus(RecordStatus.PENDING_REVIEW);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> reviewReasons(List<VariantClassification> recs) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        ReviewSettings review = reviewSettings.current();
        Map<String, String> out = new LinkedHashMap<>();
        for (VariantClassification rec : recs) {
            String why = reviewReason(rec, review);
            if (rec.status() == RecordStatus.PENDING_REVIEW && why != null) {
                out.put(rec.atin(), why);
            }
        }
        return out;
    }

    /** What sends the record to a person under the risk rule; null when the rule would approve it (mode ALL: say so). */
    private String reviewReason(VariantClassification rec, ReviewSettings review) {
        TaxProfile profile;
        try {
            profile = engine.profile(rec.categoryCode());
        } catch (NotFoundException ex) {
            return "No matching rule: there is no tax profile for this category.";
        }
        Resolution r = resolve(rec);
        RiskReviewDecision d = new RiskReviewPolicy(review).decide(profile, r, rec.allFacts());
        if (!d.autoApprove()) {
            return d.rule();
        }
        return review.riskBased() ? null : "Review mode is ALL: every item goes to a person, even when the rules resolve it.";
    }

    @Override
    @Transactional(readOnly = true)
    public VariantClassificationRepository.Page list(RecordStatus status, String categoryCode, String sellerOrgId,
                                                     String q, boolean auditOnly, int page, int size) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return records.list(new VariantClassificationRepository.Filter(status, categoryCode, sellerOrgId, q, auditOnly),
                page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewList search(VariantClassificationRepository.Filter filter, int page, int size) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        VariantClassificationRepository.Page found = records.list(filter, page, size);
        return new ReviewList(found.items(), found.total(),
                records.sellerOrgIds(found.items().stream().map(VariantClassification::atin).toList()));
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
        String rules = r.ruleVersion();
        if (rec.approved() && rec.staffFacts().equals(staff) && rec.refinedProviderCodes().equals(refined)) {
            if (!rec.auditItem()) {
                return view(rec);
            }
            VariantClassification closed = records.update(reviewed.withAuditClosed(by, now));
            records.addAudit(atin, new AuditEntry(now, by, AuditAction.AUDIT_CONFIRMED,
                    c.reason() == null || c.reason().isBlank() ? "Audit sample confirmed: the classification is right"
                            : c.reason().trim(), rules, summary(rec, rec.resolution()), summary(closed, r)));
            return view(closed);
        }
        VariantClassification approved = withStatus(reviewed, RecordStatus.APPROVED, r, rules, now);
        VariantClassification saved = records.update(rec.auditItem() ? approved.withAuditClosed(by, now) : approved);
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

    @Override
    @Transactional
    public ClassificationView requestInformation(String atin, String message) {
        Actor actor = access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN, TaxReturnsPermissions.TAX_CONFIGURE);
        String text = message == null ? "" : message.trim();
        if (text.length() < MIN_MESSAGE || text.length() > MAX_MESSAGE) {
            throw new ReturnRuleViolation("tax.invalid-information-request", "Say what the seller should provide ("
                    + MIN_MESSAGE + " to " + MAX_MESSAGE + " characters)", Map.of("message", "Between " + MIN_MESSAGE
                    + " and " + MAX_MESSAGE + " characters"));
        }
        VariantClassification rec = load(atin);
        Instant now = clock.instant();
        String by = Access.describe(actor);
        VariantClassification saved = records.update(rec.withInformationRequest(
                new InformationRequest(text, by, now), now));
        String rules = engine.snapshot().ruleVersion();
        records.addAudit(atin, new AuditEntry(now, by, AuditAction.INFORMATION_REQUESTED, text, rules,
                summary(rec, rec.resolution()), summary(saved, rec.resolution())));
        events.variant(saved, rec.status().name(), rec.resolution(), text, by, now);
        return view(saved);
    }

    private VariantClassification load(String atin) {
        return records.find(atin).orElseThrow(() -> new NotFoundException("tax.classification-not-found",
                "No classification record for " + atin));
    }

    private ClassificationView view(VariantClassification rec) {
        Resolution r = resolve(rec);
        return new ClassificationView(rec, r, records.evidence(rec.atin()), records.audit(rec.atin()),
                records.decisions(rec.atin()), records.ordersAffected(rec.atin()),
                rec.status() == RecordStatus.PENDING_REVIEW ? reviewReason(rec, reviewSettings.current()) : null);
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

    /**
     * Who looks at a classification: in RISK_BASED mode (the default) the risk rule decides (and may sample the item for
     * audit); in ALL mode the configured approval policy does, and by default every record goes to a reviewer. The approval
     * policy is ignored in RISK_BASED mode.
     */
    private Review review(VariantClassification rec, Resolution r, ReviewSettings settings) {
        TaxProfile profile = engine.profile(rec.categoryCode());
        if (settings.riskBased()) {
            RiskReviewPolicy policy = new RiskReviewPolicy(settings);
            RiskReviewDecision d = policy.decide(profile, r, rec.allFacts());
            return new Review(!d.autoApprove(), d.rule(), true, d.autoApprove() && policy.sampledForAudit(rec.atin()),
                    settings.auditPercent());
        }
        boolean needs = props.tax().classification().approvalPolicy().requiresReview(profile, r);
        return new Review(needs, "Approved by policy " + props.tax().classification().approvalPolicy(), false, false, 0);
    }

    /** Marks an APPROVED record as approved by the risk rule: classified and approved by {@code auto}, maybe sampled. */
    private VariantClassification markAutoApproved(VariantClassification rec, Review rv, Instant now) {
        if (rv == null || !rv.risk() || rv.needsReview() || !rec.approved()) {
            return rec;
        }
        return rec.withAutoApproval(AUTO_RISK, now, rv.sampled() ? now : null);
    }

    private void auditAutoApproval(VariantClassification saved, Review rv, Resolution r, String rules,
                                   Map<String, Object> before, Instant now) {
        Map<String, Object> after = summary(saved, r);
        if (rv != null && rv.risk()) {
            after.put("facts", saved.allFacts());
            after.put("classifiedBy", AUTO_RISK);
            after.put("rule", rv.rule());
            records.addAudit(saved.atin(), new AuditEntry(now, AUTO_RISK, AuditAction.APPROVED, rv.rule(), rules, before,
                    after));
            if (saved.auditItem()) {
                records.addAudit(saved.atin(), new AuditEntry(now, AUTO_RISK, AuditAction.AUDIT_SAMPLED,
                        "Placed in the audit sample (" + rv.auditPercent() + "% of auto-approved items, picked by a fixed "
                                + "hash of the variant ATIN). It stays live until a reviewer confirms or reclassifies it.",
                        rules, before, after));
            }
            return;
        }
        records.addAudit(saved.atin(), new AuditEntry(now, AUTO, AuditAction.APPROVED, rv == null
                ? "Approved by policy " + props.tax().classification().approvalPolicy() : rv.rule(), rules, before, after));
    }

    /**
     * @param needsReview  a person must review it
     * @param rule         the rule that applied, in plain words
     * @param risk         decided by the risk-based rule
     * @param sampled      auto-approved and picked for the audit sample
     * @param auditPercent the sampling percentage in force
     */
    private record Review(boolean needsReview, String rule, boolean risk, boolean sampled, int auditPercent) {
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
