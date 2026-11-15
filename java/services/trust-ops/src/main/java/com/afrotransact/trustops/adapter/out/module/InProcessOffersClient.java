package com.afrotransact.trustops.adapter.out.module;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.offers.api.SellerStandingApi;
import com.afrotransact.trustops.port.OffersClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link OffersClient} through offers' published {@link SellerStandingApi} inside this JVM (mode {@code in-process}), entered as
 * trust-ops' caller principal (scope {@code offers:write} for the push). The push carries the rating offers already holds (a
 * missing rating is null), as the HTTP client reads it first.
 */
@Component
@ConditionalOnExpression("'${at.trust-ops.offers.mode:${at.trust-ops.mode:http}}' == 'in-process'")
class InProcessOffersClient implements OffersClient {

    private static final String SERVICE = "offers";

    private final ObjectProvider<SellerStandingApi> api;
    private final ModuleUpstream upstream;

    InProcessOffersClient(ObjectProvider<SellerStandingApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerStandingApi.class, true, false).length > 0,
                "trust-ops", "offers", "at.trust-ops.offers.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void pushPerformance(String orgId, Double onTimeRate, Double defectRate, boolean restricted) {
        Double rating = upstream.call(SERVICE, () -> api.getObject().rating(orgId)).orElse(null);
        upstream.run(SERVICE, () -> api.getObject().updatePerformance(orgId, rating, onTimeRate, defectRate, restricted));
    }
}
