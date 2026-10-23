package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.ApplicationRules;
import com.afrotransact.seller.domain.BusinessDetailsSection;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.PlanSection;
import com.afrotransact.seller.domain.PlanVisibility;
import com.afrotransact.seller.domain.RuleContext;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.TaxIds;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.FieldCipher;
import com.afrotransact.seller.port.PlanRepository;
import org.springframework.stereotype.Component;

/** Builds {@link ApplicationView}s: progress, first-charge sentence, documents and PII masking. */
@Component
class ApplicationViews {

    private final ApplicationRules rules;
    private final PlanRepository plans;
    private final FieldCipher cipher;
    private final SellerSettings settings;
    private final TrialService trials;
    private final PlanFeeCollectionService planFees;
    private final Clock clock;

    ApplicationViews(ApplicationRules rules, PlanRepository plans, FieldCipher cipher, SellerSettings settings,
                     TrialService trials, PlanFeeCollectionService planFees, Clock clock) {
        this.rules = rules;
        this.plans = plans;
        this.cipher = cipher;
        this.settings = settings;
        this.trials = trials;
        this.planFees = planFees;
        this.clock = clock;
    }

    RuleContext context(Application app) {
        return new RuleContext(app.kind(), settings.liveCountries(), LocalDate.now(clock));
    }

    /** The chosen plan when new organisations may still choose it (published, not retired). */
    Optional<Plan> plan(ApplicationData data) {
        return data.plan() == null || data.plan().planCode() == null ? Optional.empty()
                : plans.find(data.plan().planCode()).filter(Plan::isOpen);
    }

    /**
     * The plan step's choice: a public plan (or an invite-only plan the organisation was invited to), and a promo code
     * that names a campaign for this kind, plan and sign-up time (422 seller.invalid-promo-code).
     */
    void checkPlanChoice(Application app, PlanSection in) {
        if (in == null || in.planCode() == null) {
            return;
        }
        Optional<Plan> plan = plans.find(in.planCode()).filter(Plan::isOpen);
        if (plan.isPresent() && plan.get().visibility() != PlanVisibility.PUBLIC
                && !(plan.get().visibility() == PlanVisibility.INVITE_ONLY
                && plans.invitedPlans(app.orgId()).contains(in.planCode()))) {
            throw new SellerRuleViolation("seller.invalid-application", "Plan " + in.planCode() + " isn't available",
                    Map.of("planCode", "Unknown plan"));
        }
        if (in.promoCode() != null && trials.promo(in.promoCode(), app.kind(), in.planCode(), signupAt(app))
                .isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-promo-code", "Promo code " + in.promoCode()
                    + " isn't valid for this plan", Map.of("promoCode", "This code isn't valid for this plan"));
        }
    }

    /** When the organisation signed up for campaign windows: submission time, or now while drafting. */
    Instant signupAt(Application app) {
        return app.submittedAt() == null ? Instant.now(clock) : app.submittedAt();
    }

    /**
     * What the applicant may see of the review history: their own steps, what was asked of them and the final decision.
     * Internal steps (a reviewer's approval, a flag, a send-back) and every staff member's identity and note stay
     * inside; the note of a request for information and of a rejection is addressed to the applicant.
     */
    static List<TimelineEntry> forApplicant(List<TimelineEntry> timeline) {
        return timeline.stream()
                .filter(t -> switch (t.action()) {
                    case STARTED, SUBMITTED, RESUBMITTED, REQUEST_INFO, FINAL_APPROVE, REJECT -> true;
                    default -> false;
                })
                .map(t -> new TimelineEntry(t.id(), t.applicationId(), t.action(), t.fromStatus(), t.toStatus(), null,
                        t.reasonCode(), t.action() == ReviewAction.REQUEST_INFO || t.action() == ReviewAction.REJECT
                        ? t.note() : null, t.items(), t.occurredAt(), null, null))
                .toList();
    }

    ApplicationView view(Application app, PiiLevel pii, List<TimelineEntry> timeline, List<DocumentAccess> log) {
        ApplicationData data = app.data();
        List<TimelineEntry> shown = pii == PiiLevel.APPLICANT ? forApplicant(timeline) : timeline;
        Optional<Plan> plan = plan(data);
        BusinessDetailsSection d = data.businessDetails();
        String masked = d == null ? null : TaxIds.masked(d.taxIdType(), d.taxIdLast4());
        String full = pii == PiiLevel.FULL && d != null && d.taxIdCipher() != null ? cipher.decrypt(d.taxIdCipher())
                : null;
        FirstCharge first = plan.isPresent() && data.plan().cadence() != null
                ? PlanPricing.firstCharge(plan.get(), data.plan().cadence(), LocalDate.now(clock),
                trials.decide(plan.get(), app.kind(), signupAt(app), data.plan().promoCode(), null).days()) : null;
        return new ApplicationView(app, pii, masked, full, steps(data.entityType()),
                rules.completedSteps(data, plan, context(app)), first, plan.map(Plan::name).orElse(null),
                documents(data), shown, log, planFees.options());
    }

    /** The wizard's steps for the entity type (owners are not shown for sole proprietors). */
    static List<OnboardingStep> steps(EntityType entityType) {
        List<OnboardingStep> all = new ArrayList<>(Arrays.asList(OnboardingStep.values()));
        if (entityType != null && !entityType.needsOwners()) {
            all.remove(OnboardingStep.OWNERS);
        }
        return all;
    }

    static List<DocumentRef> documents(ApplicationData data) {
        List<DocumentRef> out = new ArrayList<>();
        BusinessDetailsSection d = data.businessDetails();
        if (d != null) {
            add(out, d.formationDocumentAssetId(), "FORMATION_DOCUMENT", "Formation document");
            add(out, d.irsDeterminationLetterAssetId(), "IRS_DETERMINATION_LETTER", "IRS determination letter");
        }
        ComplianceSection c = data.compliance();
        if (c != null) {
            if (c.foodPermit() != null) {
                add(out, c.foodPermit().documentAssetId(), "FOOD_PERMIT", "Food permit " + nz(c.foodPermit()
                        .permitNumber()));
            }
            for (ComplianceSection.ServiceLicence s : c.services()) {
                add(out, s.documentAssetId(), "LICENCE", "Licence — " + nz(s.serviceName()));
            }
            add(out, c.insuranceDocumentAssetId(), "INSURANCE", "Insurance certificate");
        }
        return out;
    }

    private static void add(List<DocumentRef> out, String assetId, String kind, String label) {
        if (assetId != null && !assetId.isBlank()) {
            out.add(new DocumentRef(assetId, kind, label.trim()));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
