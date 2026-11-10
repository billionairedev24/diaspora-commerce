package com.afrotransact.taxreturns.application;

import java.util.Map;

import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxDecisionStamp;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import org.springframework.stereotype.Component;

/** Stamps a placed order's lines with the tax code they fall under and queues the ones classified by default for review. */
@Component
class TaxLineTagger {

    private final TaxClassifierProvider classifier;
    private final TaxClassificationRepository repository;

    TaxLineTagger(TaxClassifierProvider classifier, TaxClassificationRepository repository) {
        this.classifier = classifier;
        this.repository = repository;
    }

    /**
     * A line priced under a stored decision is stamped with its internal code and never queued by the legacy classifier;
     * only lines of older events fall back to it.
     */
    void tag(PlacedOrder order, Map<String, ProductFacts.Facts> facts, Map<String, TaxDecisionStamp> stamps) {
        for (PlacedOrder.Shipment s : order.shipments()) {
            for (PlacedOrder.Line l : s.lines()) {
                TaxDecisionStamp stamp = stamps.get(l.lineId());
                if (stamp != null) {
                    repository.tagLine(l.lineId(), stamp.internalTaxCode());
                    continue;
                }
                ProductFacts.Facts f = l.atin() == null ? null : facts.get(l.atin());
                String type = l.productType() != null ? l.productType() : f == null ? null : f.productType();
                Map<String, String> answers = f == null ? Map.of() : f.attributes();
                TaxClassification c = classifier.current().classify(type, answers);
                repository.tagLine(l.lineId(), c.taxCode());
                if (c.needsReview()) {
                    repository.recordSeen(type, relevant(answers), c, l.atin());
                }
            }
        }
    }

    /** Only the answers a rule could look at, so two products with the same answers are one queue entry. */
    private Map<String, String> relevant(Map<String, String> answers) {
        String answer = answers.get("tax_class");
        return answer == null || answer.isBlank() ? Map.of() : Map.of("tax_class", answer);
    }
}
