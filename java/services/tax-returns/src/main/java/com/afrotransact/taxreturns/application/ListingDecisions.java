package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.profile.BehaviourHash;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.springframework.stereotype.Component;

/** Keeps the stored decision per (variant ATIN, behaviour hash): written when it changes, never on a plain repeat. */
@Component
class ListingDecisions {

    private final VariantClassificationRepository records;

    ListingDecisions(VariantClassificationRepository records) {
        this.records = records;
    }

    /** Stores the decision of {@code rec} under {@code behaviour} when it differs from what is stored. */
    void save(VariantClassification rec, Map<String, Object> behaviour, String sellerOrgId, Resolution r, String by,
              Instant at) {
        String hash = BehaviourHash.of(behaviour);
        ListingDecision next = new ListingDecision(rec.atin(), hash, behaviour,
                sellerOrgId == null ? List.of() : List.of(sellerOrgId), rec.categoryCode(), rec.allFacts(),
                r.status(), r.outcome(), r.internalTaxCode(), r.providerCodes(), r.ruleVersion(), r.reason(),
                r.missingFacts(), at, by, rec.reviewedBy());
        ListingDecision stored = records.decision(rec.atin(), hash).orElse(null);
        if (stored != null) {
            List<String> orgs = new ArrayList<>(stored.sellerOrgIds());
            boolean orgNew = sellerOrgId != null && !orgs.contains(sellerOrgId);
            if (orgNew) {
                orgs.add(sellerOrgId);
            }
            if (stored.sameDecision(next) && !orgNew) {
                return;
            }
            next = new ListingDecision(next.atin(), hash, behaviour, orgs, next.categoryCode(), next.factsSnapshot(),
                    next.status(), next.outcome(), next.internalTaxCode(), next.providerCodes(), next.ruleVersion(),
                    next.reason(), next.missingFacts(), at, by, next.approvedBy());
        }
        records.saveDecision(next);
    }
}
