package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import com.afrotransact.seller.domain.RuleContext;
import com.afrotransact.seller.domain.TaxIds;
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
    private final Clock clock;

    ApplicationViews(ApplicationRules rules, PlanRepository plans, FieldCipher cipher, SellerSettings settings,
                     Clock clock) {
        this.rules = rules;
        this.plans = plans;
        this.cipher = cipher;
        this.settings = settings;
        this.clock = clock;
    }

    RuleContext context(Application app) {
        return new RuleContext(app.kind(), settings.liveCountries(), LocalDate.now(clock));
    }

    Optional<Plan> plan(ApplicationData data) {
        return data.plan() == null || data.plan().planCode() == null ? Optional.empty()
                : plans.find(data.plan().planCode());
    }

    ApplicationView view(Application app, PiiLevel pii, List<TimelineEntry> timeline, List<DocumentAccess> log) {
        ApplicationData data = app.data();
        Optional<Plan> plan = plan(data);
        BusinessDetailsSection d = data.businessDetails();
        String masked = d == null ? null : TaxIds.masked(d.taxIdType(), d.taxIdLast4());
        String full = pii == PiiLevel.FULL && d != null && d.taxIdCipher() != null ? cipher.decrypt(d.taxIdCipher())
                : null;
        FirstCharge first = plan.isPresent() && data.plan().cadence() != null
                ? PlanPricing.firstCharge(plan.get(), data.plan().cadence(), LocalDate.now(clock)) : null;
        return new ApplicationView(app, pii, masked, full, steps(data.entityType()),
                rules.completedSteps(data, plan, context(app)), first, plan.map(Plan::name).orElse(null),
                documents(data), timeline, log);
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
