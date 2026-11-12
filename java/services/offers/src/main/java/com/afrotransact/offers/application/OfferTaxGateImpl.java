package com.afrotransact.offers.application;

import java.util.Map;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.port.TaxDecisions;
import com.afrotransact.offers.port.TaxDecisions.DecisionState;
import com.afrotransact.offers.port.TaxDecisions.TaxUnavailable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class OfferTaxGateImpl implements OfferTaxGate {

    private static final Logger LOG = LoggerFactory.getLogger(OfferTaxGateImpl.class);

    private final TaxDecisions decisions;

    OfferTaxGateImpl(TaxDecisions decisions) {
        this.decisions = decisions;
    }

    @Override
    public Verdict check(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
        try {
            DecisionState stored = decisions.find(atin, sellerOrgId);
            if (stored.resolved()) {
                return Verdict.ok();
            }
            // None yet (or not resolvable yet): ask tax-returns to decide from the page's facts. It never guesses.
            DecisionState asked = decisions.request(atin, sellerOrgId, sellerBehaviour);
            if (asked.resolved()) {
                return Verdict.ok();
            }
            return Verdict.held(reason(asked));
        } catch (TaxUnavailable ex) {
            LOG.warn("tax-returns unavailable for {} / {}: {}", sellerOrgId, atin, ex.getMessage());
            return Verdict.held(Offer.HOLD_UNAVAILABLE);
        }
    }

    private static String reason(DecisionState state) {
        return "MISSING".equals(state.status()) ? Offer.HOLD_DECISION_MISSING : "TAX_" + state.status();
    }
}
