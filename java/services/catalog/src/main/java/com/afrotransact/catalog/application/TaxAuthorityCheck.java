package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxDecisionAuthority.Answer;
import com.afrotransact.catalog.port.TaxDecisionAuthority.Ask;
import com.afrotransact.catalog.port.TaxDecisionAuthority.Unavailable;
import com.afrotransact.catalog.port.TaxGateMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Judges listings against tax-returns' stored decision (the authority checkout reads): a product is OK only when
 * tax-returns answers RESOLVED for every one of its variants. One batched call per page of products. When tax-returns
 * cannot be reached the call fails with {@link Unavailable} (counted and logged here) and nobody is marked OK.
 */
@Component
class TaxAuthorityCheck {

    private static final Logger LOG = LoggerFactory.getLogger(TaxAuthorityCheck.class);

    private final TaxDecisionAuthority authority;
    private final TaxGateMetrics metrics;

    TaxAuthorityCheck(TaxDecisionAuthority authority, TaxGateMetrics metrics) {
        this.authority = authority;
        this.metrics = metrics;
    }

    /** False only when the authority is switched off explicitly (tests). */
    boolean consulted() {
        return authority.consulted();
    }

    /**
     * What tax-returns says about one product.
     *
     * @param resolved   every variant is RESOLVED in tax-returns
     * @param reasonCode {@code TAX_RETURNS_<status>} of the most pressing variant, null when resolved
     * @param blockers   per variant, plain words (no ids), keyed like the form's fields
     * @param variants   per variant ATIN: the answer (null when tax-returns gave none)
     * @param noRecord   some variant has no record in tax-returns at all (the facts were never reported)
     */
    record Verdict(boolean resolved, String reasonCode, Map<String, String> blockers, Map<String, Answer> variants,
                   boolean noRecord) {
    }

    /**
     * @throws Unavailable when tax-returns cannot be reached (the failure is recorded; nothing is decided)
     */
    Map<UUID, Verdict> verdicts(Collection<Product> products) {
        List<Ask> asks = new ArrayList<>();
        for (Product p : products) {
            for (Variant v : p.variants()) {
                asks.add(new Ask(v.atin().value(), p.owner().orgId()));
            }
        }
        Map<String, Answer> answers;
        try {
            answers = asks.isEmpty() ? Map.of() : authority.ask(asks);
        } catch (Unavailable ex) {
            metrics.authorityUnreachable();
            LOG.error("tax-returns could not be asked about {} listing(s): their state is kept, nothing is marked OK",
                    products.size(), ex);
            throw ex;
        }
        metrics.authorityChecked();
        Map<UUID, Verdict> out = new LinkedHashMap<>();
        for (Product p : products) {
            out.put(p.id(), judge(p, answers));
        }
        return out;
    }

    private static Verdict judge(Product p, Map<String, Answer> answers) {
        Map<String, String> blockers = new LinkedHashMap<>();
        Map<String, Answer> perVariant = new LinkedHashMap<>();
        String worst = null;
        boolean noRecord = false;
        for (int i = 0; i < p.variants().size(); i++) {
            String atin = p.variants().get(i).atin().value();
            Answer a = answers.get(atin);
            perVariant.put(atin, a);
            if (a != null && a.resolved()) {
                continue;
            }
            String status = a == null || a.decisionStatus() == null ? "NO_DECISION" : a.decisionStatus();
            if (a == null || a.recordStatus() == null) {
                noRecord = true;
            }
            blockers.put("variants[" + i + "].tax", plain(status));
            if (worst == null) {
                worst = status;
            }
        }
        if (p.variants().isEmpty()) {
            blockers.put("variants", "This product has no variants.");
            worst = "NO_VARIANTS";
        }
        return new Verdict(blockers.isEmpty(), worst == null ? null : "TAX_RETURNS_" + worst, blockers, perVariant,
                noRecord);
    }

    /** Words for sellers and staff: no ids, no tax terms beyond what the review queue already says. */
    static String plain(String decisionStatus) {
        return switch (decisionStatus) {
            case "NEEDS_APPROVAL" -> "Tax review has not approved this item yet.";
            case "MISSING_FACTS" -> "Tax review has no complete record of this item yet.";
            case "BLOCKED" -> "Tax review has blocked this item.";
            case "REFINE_CODE" -> "Tax review needs one more detail before it can approve this item.";
            case "CONFLICT_OPEN" -> "A tax question about this item is still open.";
            default -> "Tax review has no decision for this item yet.";
        };
    }
}
