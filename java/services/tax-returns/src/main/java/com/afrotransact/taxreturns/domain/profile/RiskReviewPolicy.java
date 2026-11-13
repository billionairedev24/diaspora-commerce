package com.afrotransact.taxreturns.domain.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
            return review(statusReason(profile));
        }
        if (alwaysReview.contains(profile.categoryCode())) {
            return review("Regulated category: " + profile.name() + " always goes to a person.");
        }
        if (resolution == null) {
            return review("No decision: the rules could not be read for " + profile.name() + ".");
        }
        if (resolution.status() != ResolveStatus.RESOLVED) {
            return review(resolutionReason(profile, resolution));
        }
        String evidence = missingEvidence(profile, facts);
        if (evidence != null) {
            return review("Missing proof: " + profile.name() + " needs " + evidence + " and none is attached.");
        }
        return new RiskReviewDecision(true, "Risk-based review: the category " + profile.name() + " is ACTIVE, the rules "
                + "resolved cleanly (" + resolution.outcome() + (resolution.rowId() == null ? "" : ", row "
                + resolution.rowId()) + "), no required evidence is missing and the category is not on the "
                + "always-review list.");
    }

    /** Why a category that is not ACTIVE sends its items to a person, in plain words. */
    private static String statusReason(TaxProfile profile) {
        String name = profile.name();
        return switch (profile.status()) {
            case CONFLICT_OPEN -> "Conflict: " + name + " has an open decision about which tax code applies.";
            case NEEDS_APPROVAL -> "Needs sign-off: the tax professional has not approved the rules for " + name + " yet.";
            case REFINE_CODE -> "Code to verify: " + name + " uses a generic provider code that must be verified for "
                    + "this item.";
            case BLOCKED_BUNDLE -> "Blocked: " + name + " is a mixed bundle that must be itemised.";
            default -> name + " is " + profile.status() + ", so a tax reviewer must look at it.";
        };
    }

    /** Why rules that did not resolve cleanly send an item to a person, in plain words. */
    private static String resolutionReason(TaxProfile profile, Resolution resolution) {
        String name = profile.name();
        return switch (resolution.status()) {
            case MISSING_FACTS -> resolution.missingFacts().isEmpty()
                    ? "No matching rule: the answers given fit none of the rules for " + name + "."
                    : "Missing answer: the seller has not told us "
                            + resolution.missingFacts().stream().map(MissingFact::label).collect(Collectors.joining(", "))
                            + ".";
            case BLOCKED -> "Blocked: as described this cannot be sold (" + resolution.reason() + ").";
            case NEEDS_APPROVAL -> "Needs sign-off: the tax professional has not approved the rules for " + name + " yet.";
            case REFINE_CODE -> "Code to verify: " + name + " uses a generic provider code that must be verified for "
                    + "this item.";
            case CONFLICT_OPEN -> "Conflict: " + name + " has an open decision about which tax code applies.";
            case RESOLVED -> throw new IllegalArgumentException("A resolved decision needs no reason to review");
        };
    }

    /** The label of the first evidence fact the profile requires for these facts that has no value, else null. */
    private String missingEvidence(TaxProfile profile, Map<String, Object> facts) {
        Set<String> required = evaluator.requiredIds(profile, facts);
        Map<String, ProfileFact> byId = profile.factsById();
        for (String id : required) {
            ProfileFact f = byId.get(id);
            if (f != null && f.definition().evidence() && isBlank(facts.get(id))) {
                return f.definition().label();
            }
        }
        return null;
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
