package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
class LineDecisionServiceImpl implements LineDecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(LineDecisionServiceImpl.class);

    private final VariantClassificationRepository records;
    private final VariantClassificationService classifications;
    private final ProductFacts productFacts;
    private final DecisionEngine engine;
    private final ListingDecisions listingDecisions;
    private final TaxReturnsProperties props;
    private final TaxTelemetry telemetry;
    private final Clock clock;

    LineDecisionServiceImpl(VariantClassificationRepository records, VariantClassificationService classifications,
                            ProductFacts productFacts, DecisionEngine engine, ListingDecisions listingDecisions,
                            TaxReturnsProperties props, TaxTelemetry telemetry, Clock clock) {
        this.records = records;
        this.classifications = classifications;
        this.productFacts = productFacts;
        this.engine = engine;
        this.listingDecisions = listingDecisions;
        this.props = props;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public Map<String, LineDecision> decide(List<Request> lines, TaxAddress shipTo, LocalDate saleDate) {
        String rules = engine.snapshot().ruleVersion();
        Map<String, LineDecision> out = new LinkedHashMap<>();
        List<String> states = props.tax().classification().ruleStates();
        if (!"US".equals(shipTo.country()) || !states.contains(shipTo.state())) {
            for (Request r : lines) {
                out.put(r.key(), blocked(ResolveStatus.BLOCKED, rules, null, "The tax rules cover "
                        + String.join(", ", states) + " only, not " + shipTo.stateJurisdiction()));
            }
            return out;
        }
        Map<String, VariantClassification> found = load(lines);
        Map<Request, Resolution> resolved = new LinkedHashMap<>();
        Set<String> internalCodes = new LinkedHashSet<>();
        for (Request r : lines) {
            VariantClassification rec = r.atin() == null ? null : found.get(r.atin());
            LineDecision early = early(r, rec, rules);
            if (early != null) {
                out.put(r.key(), early);
                continue;
            }
            Map<String, Object> behaviour = behaviourOf(r, rec);
            Resolution res;
            try {
                res = engine.resolveRecord(rec, behaviour, saleDate);
            } catch (NotFoundException ex) {
                out.put(r.key(), blocked(ResolveStatus.BLOCKED, rules, rec.categoryCode(),
                        "No tax profile for category " + rec.categoryCode()));
                continue;
            }
            if (!res.chargeable()) {
                out.put(r.key(), blocked(res.status(), res.ruleVersion(), rec.categoryCode(), res.reason()));
                continue;
            }
            resolved.put(r, res);
            internalCodes.add(res.internalTaxCode());
        }
        Map<String, Map<String, String>> usable = engine.usableCodes(internalCodes,
                props.tax().classification().requireVerifiedCodes());
        Instant now = clock.instant();
        for (Map.Entry<Request, Resolution> e : resolved.entrySet()) {
            Request r = e.getKey();
            Resolution res = e.getValue();
            VariantClassification rec = found.get(r.atin());
            Map<String, String> codes = new LinkedHashMap<>(usable.getOrDefault(res.internalTaxCode(), Map.of()));
            codes.putAll(rec.refinedProviderCodes());
            LineDecision d = new LineDecision(ResolveStatus.RESOLVED, res.outcome(), res.internalTaxCode(), codes,
                    res.ruleVersion(), null, rec.categoryCode(), res.reason(), explanationOf(res, rec.categoryCode()));
            telemetry.quoteLine("RESOLVED", res.outcome().name());
            out.put(r.key(), d);
            try {
                listingDecisions.save(rec, behaviourOf(r, rec), r.sellerOrgId(), res, "SYSTEM:quote", now);
            } catch (DataAccessException ex) {
                LOG.warn("Could not store the decision of {}: {}", r.atin(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Why an exempt line carries no tax, in the shopper's words: the category and the rule's own wording from the stored
     * decision ("Baby food & formula: no sales tax applies. Standard qualifying item."). Only a RESOLVED EXEMPT decision
     * has one; anything else is null, so a screen can never explain a missing tax with an unresolved decision.
     */
    private String explanationOf(Resolution res, String categoryCode) {
        if (res.status() != ResolveStatus.RESOLVED || res.outcome() != Outcome.EXEMPT) {
            return null;
        }
        String name = engine.profile(categoryCode).name();
        String why = res.reason() == null ? "" : res.reason().strip();
        String tail = why.isEmpty() ? "" : " " + (why.endsWith(".") ? why : why + ".");
        return name + ": no sales tax applies." + tail;
    }

    /**
     * The behaviour the line is decided under: what the caller sent; when it sent none, the owner's listing behaviour that
     * catalog reported, but only for the owner's own line (another seller's behaviour is never assumed).
     */
    private static Map<String, Object> behaviourOf(Request r, VariantClassification rec) {
        boolean owner = r.sellerOrgId() != null && r.sellerOrgId().equals(rec.ownerOrgId());
        return r.sellerBehaviour().isEmpty() && owner ? rec.sellerBehaviour() : r.sellerBehaviour();
    }

    /** A blocked decision when the record cannot be charged at all (no ATIN, no record, not approved), else null. */
    private LineDecision early(Request r, VariantClassification rec, String rules) {
        if (r.atin() == null || r.atin().isBlank()) {
            return blocked(ResolveStatus.MISSING_FACTS, rules, null, "The line has no variant ATIN");
        }
        if (rec == null) {
            return blocked(ResolveStatus.MISSING_FACTS, rules, null, "No tax classification record for " + r.atin());
        }
        if (rec.status() == RecordStatus.APPROVED) {
            return null;
        }
        ResolveStatus status = switch (rec.status()) {
            case MISSING_FACTS -> ResolveStatus.MISSING_FACTS;
            case REJECTED -> ResolveStatus.BLOCKED;
            default -> ResolveStatus.NEEDS_APPROVAL;
        };
        return blocked(status, rules, rec.categoryCode(), "The classification of " + r.atin() + " is "
                + rec.status() + ", not approved");
    }

    private LineDecision blocked(ResolveStatus status, String rules, String category, String detail) {
        telemetry.quoteLine(status.name(), "BLOCKED");
        return LineDecision.blocked(status, rules, category, detail);
    }

    /** The records of the lines' ATINs; variants without one are asked of catalog once and ingested for review. */
    private Map<String, VariantClassification> load(List<Request> lines) {
        Set<String> atins = new LinkedHashSet<>();
        Map<String, Request> firstLine = new LinkedHashMap<>();
        for (Request r : lines) {
            if (r.atin() != null && !r.atin().isBlank()) {
                atins.add(r.atin());
                firstLine.putIfAbsent(r.atin(), r);
            }
        }
        Map<String, VariantClassification> found = new LinkedHashMap<>(records.findAll(atins));
        Set<String> missing = new LinkedHashSet<>(atins);
        missing.removeAll(found.keySet());
        if (missing.isEmpty()) {
            return found;
        }
        Map<String, ProductFacts.Facts> facts = productFacts.lookup(missing);
        boolean ingested = false;
        for (String atin : missing) {
            ProductFacts.Facts f = facts.get(atin);
            if (f == null || f.categoryCode() == null || f.categoryCode().isBlank()) {
                continue;
            }
            Request line = firstLine.get(atin);
            // The listing behaviour catalog reports is its owner's: the record belongs to that organisation, and a line
            // of another seller never inherits it (it carries its own behaviour, or none and is blocked if needed).
            String owner = f.ownerOrgId() != null ? f.ownerOrgId() : line.sellerOrgId();
            Map<String, Object> behaviour = f.ownerOrgId() == null && f.sellerBehaviour().isEmpty()
                    ? line.sellerBehaviour() : f.sellerBehaviour();
            classifications.onCatalogFactsChanged(new VariantClassificationService.CatalogFactsChanged(null,
                    owner, f.categoryCode(), f.productType(), behaviour,
                    List.of(new VariantClassificationService.Variant(atin, null, f.taxFacts(), false)),
                    "SYSTEM:quote", clock.instant()));
            ingested = true;
        }
        if (ingested) {
            found = new LinkedHashMap<>(records.findAll(atins));
        }
        return found;
    }
}
