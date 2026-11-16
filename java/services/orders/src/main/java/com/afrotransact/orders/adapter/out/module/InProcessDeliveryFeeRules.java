package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.domain.FeeRuleSnapshot;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link DeliveryFeeRules} through delivery-runs' published {@link DeliveryRunsApi#carrierFee} (mode {@code in-process}), entered as
 * orders' caller principal (scope {@code delivery-runs:reserve}). A checkout never fails because delivery-runs cannot answer: it falls
 * back to its own flat carrier rates (logged), as with the HTTP adapter.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "delivery-runs", value = ModuleMode.IN_PROCESS)
class InProcessDeliveryFeeRules implements DeliveryFeeRules {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessDeliveryFeeRules.class);
    private static final String SERVICE = "delivery-runs";

    private final ObjectProvider<DeliveryRunsApi> api;
    private final ModuleUpstream upstream;

    InProcessDeliveryFeeRules(ObjectProvider<DeliveryRunsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(DeliveryRunsApi.class, true, false).length > 0, "orders",
                "delivery-runs", "at.orders.deliveryruns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<FeeRuleSnapshot> carrier(String currency, Long quoteMinor, long itemsMinor) {
        try {
            return upstream.call(SERVICE, () -> api.getObject().carrierFee(currency, quoteMinor, itemsMinor))
                    .map(DeliveryRulesMapping::snapshot);
        } catch (UpstreamFailure f) {
            LOG.warn("delivery-runs carrier fee rule unavailable ({}); using the flat carrier rate", f.code());
            return Optional.empty();
        }
    }
}
