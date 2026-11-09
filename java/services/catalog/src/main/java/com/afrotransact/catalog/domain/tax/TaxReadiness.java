package com.afrotransact.catalog.domain.tax;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;

/**
 * The tax publish gate of a product: it can go live only when every variant is {@link TaxStatus#READY}.
 *
 * @param status          READY, or the most pressing status of a variant
 * @param categoryStatus  the category's tax gate (ACTIVE, NEEDS_APPROVAL ...) or null
 * @param categoryMessage plain words about the category gate, when it is not open
 */
public record TaxReadiness(UUID productId, TaxStatus status, String categoryCode, String categoryStatus,
                           String categoryMessage, String ruleVersion, List<VariantReadiness> variants) {

    /** Order of pressing: what the seller or staff must resolve first. */
    private static final List<TaxStatus> PRECEDENCE = List.of(TaxStatus.CONFLICT_OPEN, TaxStatus.BLOCKED,
            TaxStatus.NEEDS_APPROVAL, TaxStatus.MISSING_FACTS, TaxStatus.REJECTED, TaxStatus.REFINE_CODE,
            TaxStatus.PENDING_REVIEW, TaxStatus.READY);

    public record VariantReadiness(String atin, String sellerSku, TaxStatus status, List<MissingTaxFact> missingTaxFacts,
                                   String reason) {
    }

    public TaxReadiness {
        variants = List.copyOf(variants);
    }

    public boolean ready() {
        return status == TaxStatus.READY;
    }

    /** Readiness from the stored tax state of the variants. */
    public static TaxReadiness of(Product p, String categoryCode, String categoryStatus) {
        List<VariantReadiness> list = p.variants().stream().map(TaxReadiness::variant).toList();
        TaxStatus worst = TaxStatus.READY;
        for (VariantReadiness v : list) {
            if (PRECEDENCE.indexOf(v.status()) < PRECEDENCE.indexOf(worst)) {
                worst = v.status();
            }
        }
        if (list.isEmpty()) {
            worst = TaxStatus.MISSING_FACTS;
        }
        String ruleVersion = p.variants().stream().map(v -> v.tax().decision()).filter(d -> d != null)
                .map(TaxDecision::ruleVersion).findFirst().orElse(null);
        return new TaxReadiness(p.id(), worst, categoryCode, categoryStatus, TaxGate.message(categoryStatus),
                ruleVersion, list);
    }

    private static VariantReadiness variant(Variant v) {
        VariantTax t = v.tax();
        String reason = t.ready() ? null : TaxGate.variantMessage(t.status());
        return new VariantReadiness(v.atin().value(), v.sellerSku(), t.status(), t.missing(), reason);
    }

    /** What stops the product from going live, keyed like the form's fields (empty when ready). */
    public Map<String, String> blockers() {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < variants.size(); i++) {
            VariantReadiness v = variants.get(i);
            if (v.status() == TaxStatus.READY) {
                continue;
            }
            if (v.missingTaxFacts().isEmpty()) {
                out.put("variants[" + i + "].tax", v.reason());
            }
            for (MissingTaxFact m : v.missingTaxFacts()) {
                out.put("variants[" + i + "].taxFacts." + m.factId(), m.message());
            }
        }
        return out;
    }
}
