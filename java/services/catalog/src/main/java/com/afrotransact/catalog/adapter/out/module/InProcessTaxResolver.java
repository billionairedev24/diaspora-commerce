package com.afrotransact.catalog.adapter.out.module;

import java.util.List;

import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link TaxResolver} through tax-returns' published API inside this JVM (mode {@code in-process}): the same resolution as
 * {@code POST /v1/tax/classifications/resolve}, entered as catalog's caller principal (scope {@code tax:classify}). A failure
 * is {@link TaxServiceUnavailable}, as over HTTP, so callers cannot tell the modes apart.
 *
 * <p>The API is looked up on each call, not at construction: catalog and tax-returns call each other, and a constructor
 * dependency in both directions is a circular bean reference.
 */
public class InProcessTaxResolver implements TaxResolver {

    static final String MODULE = "catalog";

    private final ObjectProvider<TaxClassificationApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    public InProcessTaxResolver(ObjectProvider<TaxClassificationApi> api, ObjectProvider<CallerPrincipals> callers) {
        this.api = api;
        this.callers = callers;
    }

    @Override
    public Resolution resolve(Request request) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            TaxClassificationApi.Resolution r = InProcessCalls.as(caller, () -> api.getObject().resolve(
                    new TaxClassificationApi.ResolveRequest(request.categoryCode(), request.productTypeCode(),
                            request.facts(), request.sellerBehaviour())));
            List<MissingTaxFact> missing = r.missingFacts().stream()
                    .map(m -> new MissingTaxFact(m.factId(), m.label(), m.message())).toList();
            return new Resolution(r.status(), r.outcome(), r.internalTaxCode(), r.rowId(), r.rateDisplay(), missing,
                    r.ruleVersion(), r.reason());
        } catch (TaxClassificationApi.UnknownTaxProfileException ex) {
            throw new TaxProfileNotFound(request.categoryCode());
        } catch (RuntimeException ex) {
            throw new TaxServiceUnavailable("tax-returns refused or failed the in-process call: " + ex.getMessage(), ex);
        }
    }
}
