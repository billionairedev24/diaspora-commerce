package com.afrotransact.taxreturns.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;

/** A map-backed {@link VariantClassificationRepository} with the real optimistic-lock behaviour. */
final class InMemoryClassifications implements VariantClassificationRepository {

    final Map<String, VariantClassification> records = new LinkedHashMap<>();
    final Map<String, List<AuditEntry>> audits = new LinkedHashMap<>();
    final Map<String, ListingDecision> decisions = new LinkedHashMap<>();

    @Override
    public Optional<VariantClassification> find(String atin) {
        return Optional.ofNullable(records.get(atin));
    }

    @Override
    public Map<String, VariantClassification> findAll(Collection<String> atins) {
        Map<String, VariantClassification> out = new LinkedHashMap<>();
        atins.forEach(a -> {
            if (records.containsKey(a)) {
                out.put(a, records.get(a));
            }
        });
        return out;
    }

    @Override
    public VariantClassification insert(VariantClassification v) {
        records.put(v.atin(), v);
        return v;
    }

    @Override
    public VariantClassification update(VariantClassification v) {
        VariantClassification stored = records.get(v.atin());
        if (stored == null || stored.version() != v.version()) {
            throw new StaleRecordException(v.atin());
        }
        VariantClassification next = new VariantClassification(v.atin(), v.gtin(), v.productId(), v.title(),
                v.ownerOrgId(), v.categoryCode(), v.productTypeCode(), v.facts(), v.staffFacts(), v.sellerBehaviour(),
                v.refinedProviderCodes(), v.status(), v.resolution(), v.ruleVersion(), v.classifiedBy(),
                v.classifiedAt(), v.reviewedBy(), v.reviewedAt(), v.decisionRef(), v.version() + 1, v.createdAt(),
                v.updatedAt());
        records.put(v.atin(), next);
        return next;
    }

    @Override
    public Page list(Filter filter, int page, int size) {
        List<VariantClassification> all = records.values().stream()
                .filter(v -> filter.status() == null || v.status() == filter.status())
                .filter(v -> filter.categoryCode() == null || v.categoryCode().equals(filter.categoryCode())).toList();
        return new Page(all, all.size());
    }

    @Override
    public void addAudit(String atin, AuditEntry entry) {
        audits.computeIfAbsent(atin, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public List<AuditEntry> audit(String atin) {
        return audits.getOrDefault(atin, List.of());
    }

    @Override
    public void addEvidence(String atin, Evidence evidence) {
    }

    @Override
    public List<Evidence> evidence(String atin) {
        return List.of();
    }

    @Override
    public Optional<ListingDecision> decision(String atin, String behaviourHash) {
        return Optional.ofNullable(decisions.get(atin + "|" + behaviourHash));
    }

    @Override
    public List<ListingDecision> decisions(String atin) {
        return decisions.values().stream().filter(d -> d.atin().equals(atin)).toList();
    }

    @Override
    public void saveDecision(ListingDecision decision) {
        decisions.put(decision.atin() + "|" + decision.behaviourHash(), decision);
    }
}
