package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.content.api.MarketEligibilityApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link MarketEligibility} through content's published {@link MarketEligibilityApi} inside this JVM (mode {@code in-process}).
 * As over HTTP the check is advisory: when content cannot answer, checkout is allowed and a missing answer is empty.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "content", value = ModuleMode.IN_PROCESS)
class InProcessMarketEligibility implements MarketEligibility {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessMarketEligibility.class);

    private final ObjectProvider<MarketEligibilityApi> api;
    private final ModuleUpstream upstream;

    InProcessMarketEligibility(ObjectProvider<MarketEligibilityApi> api, ModuleUpstream upstream,
                               ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(MarketEligibilityApi.class, true, false).length > 0,
                "orders", "content", "at.orders.content.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<Result> check(String country, String state, String zip) {
        try {
            MarketEligibilityApi.Eligibility e = upstream.call("content", () -> api.getObject()
                    .check(country == null ? "US" : country, blankToNull(state), blankToNull(zip)));
            return Optional.of(new Result(e.deliveryAllowed(), e.reason(), e.message()));
        } catch (UpstreamFailure f) {
            LOG.warn("markets eligibility unavailable ({}); allowing checkout", f.code());
            return Optional.empty();
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
