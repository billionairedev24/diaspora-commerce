package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.BehaviourHash;
import com.afrotransact.taxreturns.domain.profile.ProfileEvaluator;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.ExemptionCalendar;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import org.springframework.stereotype.Component;

/**
 * Resolves facts under the profile of a category: the domain evaluator, the system facts worked out here (the
 * exemption window from the sale date, which stays false until a holiday calendar exists), the provider codes of the
 * code map for the matched outcome, and a cache keyed by rule version (a change of a profile, status or map creates a
 * new rule version, so cached answers never outlive the rules they were made under).
 */
@Component
class DecisionEngine {

    static final String EXEMPTION_WINDOW = "exemption_window_active";
    private static final int MAX_CACHED = 10_000;

    private final ProfileCatalog catalog;
    private final ProviderCodeRepository codes;
    private final ExemptionCalendar calendar;
    private final ZoneId zone;
    private final Clock clock;
    private final ProfileEvaluator evaluator = new ProfileEvaluator();
    private final Map<String, Resolution> cache = new ConcurrentHashMap<>();

    DecisionEngine(ProfileCatalog catalog, ProviderCodeRepository codes, ExemptionCalendar calendar,
                   TaxReturnsProperties props, Clock clock) {
        this.zone = props.tax().classification().zone();
        this.clock = clock;
        this.catalog = catalog;
        this.codes = codes;
        this.calendar = calendar;
    }

    /** Today's date in the rules' jurisdiction (the sale date when a caller gives none). */
    LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    ProfileCatalog.Snapshot snapshot() {
        return catalog.current();
    }

    TaxProfile profile(String categoryCode) {
        TaxProfile p = catalog.current().profiles().get(categoryCode);
        if (p == null) {
            throw new NotFoundException("tax.profile-not-found", "No tax profile for category " + categoryCode);
        }
        return p;
    }

    /** Resolves; provider codes are every active mapped code of the outcome (verification is judged by the quote path). */
    Resolution resolve(String categoryCode, Map<String, Object> facts, Map<String, Object> behaviour,
                       Map<String, Object> context, LocalDate saleDate) {
        ProfileCatalog.Snapshot snap = catalog.current();
        TaxProfile profile = snap.profiles().get(categoryCode);
        if (profile == null) {
            throw new NotFoundException("tax.profile-not-found", "No tax profile for category " + categoryCode);
        }
        Map<String, Object> ctx = new LinkedHashMap<>(context == null ? Map.of() : context);
        ctx.putIfAbsent(EXEMPTION_WINDOW, calendar.windowActive(categoryCode, saleDate));
        String key = snap.ruleVersion() + '|' + categoryCode + '|' + BehaviourHash.of(facts) + '|'
                + BehaviourHash.of(behaviour) + '|' + BehaviourHash.of(ctx);
        Resolution hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Resolution r = evaluator.resolve(profile, facts, behaviour, ctx, snap.ruleVersion());
        if (r.internalTaxCode() != null) {
            r = r.withProviderCodes(mappedCodes(List.of(r.internalTaxCode())).getOrDefault(r.internalTaxCode(), Map.of()));
        }
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, r);
        return r;
    }

    /**
     * Resolves a variant record with a seller's behaviour. A REFINE_CODE category is lifted for a variant whose record
     * carries refined provider codes (the reviewer verified the specific code), and those codes replace the map's.
     */
    Resolution resolveRecord(VariantClassification rec, Map<String, Object> behaviour, LocalDate saleDate) {
        Resolution r = resolve(rec.categoryCode(), rec.allFacts(), behaviour, Map.of(), saleDate);
        if (r.status() == ResolveStatus.REFINE_CODE && !rec.refinedProviderCodes().isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(r.providerCodes());
            merged.putAll(rec.refinedProviderCodes());
            return r.withProviderCodes(merged).withStatus(ResolveStatus.RESOLVED,
                    "Refined provider code recorded by tax review");
        }
        if (!rec.refinedProviderCodes().isEmpty() && r.status() == ResolveStatus.RESOLVED) {
            Map<String, String> merged = new LinkedHashMap<>(r.providerCodes());
            merged.putAll(rec.refinedProviderCodes());
            return r.withProviderCodes(merged);
        }
        return r;
    }

    /** Provider code per provider for each outcome code, only entries a quote may send. */
    Map<String, Map<String, String>> usableCodes(Collection<String> internalCodes, boolean requireVerified) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (internalCodes.isEmpty()) {
            return out;
        }
        for (ProviderCode c : codes.forCodes(internalCodes)) {
            if (c.usable(requireVerified)) {
                out.computeIfAbsent(c.internalTaxCode(), k -> new LinkedHashMap<>()).put(c.provider().name(),
                        c.providerCode());
            }
        }
        return out;
    }

    private Map<String, Map<String, String>> mappedCodes(Collection<String> internalCodes) {
        return usableCodes(internalCodes, false);
    }
}
