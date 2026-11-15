package com.afrotransact.taxreturns.adapter.out.module;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.api.VariantFactsApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.taxreturns.port.ProductFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ProductFacts} through catalog's published API inside this JVM ({@code at.taxreturns.mode=in-process}): the live
 * variant read that {@code HttpProductFacts} makes over HTTP. Not cached: the HTTP adapter caches for five minutes to save
 * a round trip, here the read is a local query and an edit by the seller is seen at once. Best effort like the HTTP adapter:
 * a missing or failing answer is left out. The API is looked up per call (catalog and tax-returns call each other).
 */
@Component
@ConditionalOnModuleMode(caller = "tax-returns", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessProductFacts implements ProductFacts {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessProductFacts.class);
    private static final String MODULE = "tax-returns";

    private final ObjectProvider<VariantFactsApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    InProcessProductFacts(ObjectProvider<VariantFactsApi> api, ObjectProvider<CallerPrincipals> callers) {
        this.api = api;
        this.callers = callers;
    }

    @Override
    public Map<String, Facts> lookup(Collection<String> atins) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        Map<String, Facts> out = new LinkedHashMap<>();
        for (String atin : atins) {
            try {
                Optional<VariantFactsApi.VariantFacts> found = InProcessCalls.as(caller,
                        () -> api.getObject().liveVariantFacts(atin));
                found.ifPresent(v -> out.put(atin, new Facts(atin, v.productType(),
                        "true".equalsIgnoreCase(v.attributes().getOrDefault("perishable", "")), v.attributes(),
                        v.categoryCode(), v.taxFacts(), v.sellerBehaviour(), v.ownerOrgId())));
            } catch (RuntimeException ex) {
                LOG.debug("No catalog facts for {}: {}", atin, ex.getMessage());
            }
        }
        return out;
    }
}
