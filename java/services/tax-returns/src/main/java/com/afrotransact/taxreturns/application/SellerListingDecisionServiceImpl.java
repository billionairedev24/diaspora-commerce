package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.application.LineDecisionService.ListingStatus;
import com.afrotransact.taxreturns.application.LineDecisionService.Request;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerListingDecisionServiceImpl implements SellerListingDecisionService {

    private static final String MISSING = "MISSING";

    private final VariantClassificationRepository records;
    private final LineDecisionService lines;
    private final DecisionEngine engine;
    private final Access access;

    SellerListingDecisionServiceImpl(VariantClassificationRepository records, LineDecisionService lines,
                                     DecisionEngine engine, Access access) {
        this.records = records;
        this.lines = lines;
        this.engine = engine;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public State find(String atin, String sellerOrgId) {
        access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY, TaxReturnsPermissions.TAX_READ,
                TaxReturnsPermissions.TAX_CLASSIFY_ADMIN);
        String a = atin.trim().toUpperCase(Locale.ROOT);
        Optional<VariantClassification> record = records.find(a);
        if (record.isEmpty()) {
            return new State(a, sellerOrgId, MISSING, false, null, null, null, null,
                    List.of("There is no tax classification record for " + a + " yet"));
        }
        VariantClassification rec = record.get();
        if (rec.status() != RecordStatus.APPROVED) {
            return new State(a, sellerOrgId, unresolved(rec.status()).name(), false, null, null, null, null,
                    List.of(ReviewWording.notApproved(rec.status())));
        }
        String rulesNow = engine.snapshot().ruleVersion();
        Optional<ListingDecision> stored = records.decisions(a).stream()
                .filter(d -> d.status() == ResolveStatus.RESOLVED && d.sellerOrgIds().contains(sellerOrgId))
                .filter(d -> rulesNow.equals(d.ruleVersion())).findFirst();
        return stored.map(d -> new State(a, sellerOrgId, "RESOLVED", true, d.outcome() == null ? null : d.outcome().name(),
                        d.internalTaxCode(), d.ruleVersion(), d.decidedAt(), List.of()))
                .orElseGet(() -> new State(a, sellerOrgId, MISSING, false, null, null, null, null,
                        List.of("This seller has no tax decision on " + a + " yet")));
    }

    @Override
    @Transactional
    public State request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
        access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY, TaxReturnsPermissions.TAX_CLASSIFY_ADMIN);
        String a = atin.trim().toUpperCase(Locale.ROOT);
        ListingStatus decided = lines.decideListing(new Request(a, a, sellerOrgId, sellerBehaviour), engine.today());
        if (decided.resolved()) {
            return find(a, sellerOrgId);
        }
        return new State(a, sellerOrgId, decided.status().name(), false, null, null, null, null,
                decided.reason() == null ? List.of() : List.of(decided.reason()));
    }

    private static ResolveStatus unresolved(RecordStatus status) {
        return switch (status) {
            case MISSING_FACTS -> ResolveStatus.MISSING_FACTS;
            case REJECTED -> ResolveStatus.BLOCKED;
            default -> ResolveStatus.NEEDS_APPROVAL;
        };
    }
}
