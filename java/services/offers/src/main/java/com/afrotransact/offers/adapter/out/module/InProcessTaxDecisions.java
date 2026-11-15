package com.afrotransact.offers.adapter.out.module;

import java.util.Map;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.offers.port.TaxDecisions;
import com.afrotransact.taxreturns.api.ListingDecisionApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link TaxDecisions} through tax-returns' published API inside this JVM (mode {@code in-process}): the same answers as
 * {@code GET /v1/tax/listing-decisions} and {@code POST /v1/tax/listing-decisions/request}, entered as offers' caller principal
 * (scopes {@code tax:read} and {@code tax:classify}). Any failure, including a refusal of the principal, is
 * {@link TaxUnavailable}: the offer is held, never given the benefit of the doubt.
 */
@Component
@ConditionalOnExpression("'${at.offers.taxreturns.mode:${at.offers.mode:http}}' == 'in-process'")
class InProcessTaxDecisions implements TaxDecisions {

    private final InProcessLink<ListingDecisionApi> tax;

    InProcessTaxDecisions(ObjectProvider<ListingDecisionApi> api, ObjectProvider<CallerPrincipals> callers,
                          ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ListingDecisionApi.class, true, false).length > 0, "offers", "tax-returns",
                "at.offers.taxreturns.mode");
        this.tax = new InProcessLink<>("offers", api::getObject, callers::getObject);
    }

    @Override
    public DecisionState find(String atin, String sellerOrgId) {
        try {
            return state(tax.call(api -> api.find(atin, sellerOrgId)), atin);
        } catch (TaxUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TaxUnavailable("tax-returns could not say whether " + sellerOrgId + " has a decision on " + atin
                    + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
        try {
            return state(tax.call(api -> api.request(atin, sellerOrgId,
                    sellerBehaviour == null ? Map.of() : sellerBehaviour)), atin);
        } catch (TaxUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TaxUnavailable("tax-returns could not decide for " + sellerOrgId + " on " + atin + ": "
                    + ex.getMessage(), ex);
        }
    }

    private static DecisionState state(ListingDecisionApi.DecisionState body, String atin) {
        if (body == null || body.status() == null) {
            throw new TaxUnavailable("tax-returns returned no decision state for " + atin, null);
        }
        // Resolved only when the status says so too, as over HTTP.
        boolean resolved = body.resolved() && "RESOLVED".equals(body.status());
        return new DecisionState(body.status(), resolved, body.reasons());
    }
}
