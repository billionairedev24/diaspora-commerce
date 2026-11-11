package com.afrotransact.taxreturns.domain.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/**
 * The risk-based review rule (docs/engineering/listing-rules.md D7, category-tax-design.md section 9). A classification
 * is approved by the system only when ALL hold: the category profile is ACTIVE, the rules resolve to RESOLVED, no
 * required evidence is missing and the category is not on the always-review list. Anything else goes to a reviewer.
 * Pure and deterministic: no clock, no randomness, no I/O. It never decides a tax outcome, it only decides who looks.
 */
public final class RiskReviewPolicy {

    private static final int BUCKETS = 10_000;

    private final Set<String> alwaysReview;
    private final int auditPercent;
    private final ProfileEvaluator evaluator = new ProfileEvaluator();

    public RiskReviewPolicy(ReviewSettings settings) {
        this.alwaysReview = Set.copyOf(settings.alwaysReview());
        this.auditPercent = settings.auditPercent();
    }

    /**
     * @param profile    the category profile of the record
     * @param resolution the record resolved under today's rules
     * @param facts      every fact of the record (product facts and staff facts)
     */
    public RiskReviewDecision decide(TaxProfile profile, Resolution resolution, Map<String, Object> facts) {
        if (profile.status() != ProfileStatus.ACTIVE) {
            return review(profile.name() + " is " + profile.status() + ", so a tax reviewer must look at it.");
        }
        if (alwaysReview.contains(profile.categoryCode())) {
            return review(profile.name() + " is on the always-review list.");
        }
        if (resolution == null || resolution.status() != ResolveStatus.RESOLVED) {
            return review("The tax rules did not resolve cleanly (" + (resolution == null ? "no decision"
                    : resolution.status()) + ").");
        }
        if (evidenceMissing(profile, facts)) {
            return review("Evidence the category requires is missing.");
        }
        return new RiskReviewDecision(true, "Risk-based review: the category " + profile.name() + " is ACTIVE, the rules "
                + "resolved cleanly (" + resolution.outcome() + (resolution.rowId() == null ? "" : ", row "
                + resolution.rowId()) + "), no required evidence is missing and the category is not on the "
                + "always-review list.");
    }

    /** True when an evidence fact the profile requires for these facts has no value. */
    private boolean evidenceMissing(TaxProfile profile, Map<String, Object> facts) {
        Set<String> required = evaluator.requiredIds(profile, facts);
        Map<String, ProfileFact> byId = profile.factsById();
        for (String id : required) {
            ProfileFact f = byId.get(id);
            if (f != null && f.definition().evidence() && isBlank(facts.get(id))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    /**
     * Whether this variant is one of the audit sample: a stable hash of the ATIN, so a retried event or a second
     * evaluation of the same variant always gives the same answer and never samples it twice.
     */
    public boolean sampledForAudit(String atin) {
        if (auditPercent <= 0) {
            return false;
        }
        if (auditPercent >= ReviewSettings.MAX_AUDIT_PERCENT) {
            return true;
        }
        return bucket(atin) < auditPercent * (BUCKETS / 100);
    }

    /** 0 to 9999, uniform over ATINs. */
    static int bucket(String atin) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(atin.getBytes(StandardCharsets.UTF_8));
            long head = 0;
            for (int i = 0; i < 8; i++) {
                head = (head << 8) | (hash[i] & 0xFF);
            }
            return (int) Long.remainderUnsigned(head, BUCKETS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static RiskReviewDecision review(String why) {
        return new RiskReviewDecision(false, why);
    }
}
