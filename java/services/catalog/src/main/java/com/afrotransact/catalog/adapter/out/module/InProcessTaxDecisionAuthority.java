package com.afrotransact.catalog.adapter.out.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link TaxDecisionAuthority} through tax-returns' published API inside this JVM (mode {@code in-process}): the same batched
 * stored-decision read as {@code POST /v1/tax/listing-decisions/status}, in batches of {@value #BATCH}, entered as catalog's
 * caller principal (scope {@code tax:classify}). Any failure, including a refused scope, is {@link Unavailable}: a half answer
 * is never returned, so "unknown" is never mistaken for "resolved".
 */
public class InProcessTaxDecisionAuthority implements TaxDecisionAuthority {

    static final int BATCH = 200;

    private final ObjectProvider<TaxClassificationApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    public InProcessTaxDecisionAuthority(ObjectProvider<TaxClassificationApi> api,
                                         ObjectProvider<CallerPrincipals> callers) {
        this.api = api;
        this.callers = callers;
    }

    @Override
    public boolean consulted() {
        return true;
    }

    @Override
    public Map<String, Answer> ask(Collection<Ask> asks) {
        Map<String, Ask> distinct = new LinkedHashMap<>();
        asks.forEach(a -> distinct.putIfAbsent(a.atin(), a));
        List<Ask> all = new ArrayList<>(distinct.values());
        Map<String, Answer> out = new LinkedHashMap<>();
        try {
            CallerPrincipal caller = callers.getObject().principalFor(InProcessTaxResolver.MODULE);
            for (int from = 0; from < all.size(); from += BATCH) {
                List<TaxClassificationApi.ListingQuery> chunk = all.subList(from, Math.min(all.size(), from + BATCH))
                        .stream().map(a -> new TaxClassificationApi.ListingQuery(a.atin(), a.sellerOrgId())).toList();
                Map<String, TaxClassificationApi.ListingStatus> found = InProcessCalls.as(caller, "tax-returns",
                        () -> api.getObject().listingStatus(chunk));
                found.values().forEach(s -> out.put(s.atin(), new Answer(s.atin(), s.decisionStatus(), s.recordStatus(),
                        s.reason())));
            }
        } catch (RuntimeException ex) {
            throw new Unavailable("tax-returns could not answer the tax decision check in-process: " + ex.getMessage(),
                    ex);
        }
        return out;
    }
}
