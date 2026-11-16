package com.afrotransact.orders.adapter.out.module;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.domain.PreorderStage;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PreorderEligibility} through delivery-runs' published {@link DeliveryRunsApi#eligibility} (mode {@code in-process}), entered as
 * orders' caller principal (scope {@code delivery-runs:reserve}). Missing fields take the defaults the HTTP adapter reads them with:
 * door fee {@value PreorderEligibility#DEFAULT_DOOR_FEE_MINOR}, pickup fee 0, USD, door offered, pickup not, a window without a
 * delivery day is not a pre-order.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "delivery-runs", value = ModuleMode.IN_PROCESS)
class InProcessPreorderEligibility implements PreorderEligibility {

    private static final String SERVICE = "delivery-runs";

    private final ObjectProvider<DeliveryRunsApi> api;
    private final ModuleUpstream upstream;

    InProcessPreorderEligibility(ObjectProvider<DeliveryRunsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(DeliveryRunsApi.class, true, false).length > 0, "orders",
                "delivery-runs", "at.orders.deliveryruns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Map<String, Line> eligibility(String country, String region, String city, Collection<String> variantAtins) {
        return eligibility(country, region, city, variantAtins, 0);
    }

    @Override
    public Map<String, Line> eligibility(String country, String region, String city, Collection<String> variantAtins,
                                         int earlyAccessHours) {
        Map<String, Line> out = new LinkedHashMap<>();
        if (variantAtins.isEmpty()) {
            return out;
        }
        List<DeliveryRunsApi.Eligibility> answer = upstream.call(SERVICE, () -> api.getObject().eligibility(
                blank(country) ? "US" : country, blank(region) ? null : region, blank(city) ? null : city,
                List.copyOf(variantAtins), earlyAccessHours));
        for (DeliveryRunsApi.Eligibility e : answer) {
            if (e.atin() != null) {
                out.put(e.atin(), line(e));
            }
        }
        return out;
    }

    private static Line line(DeliveryRunsApi.Eligibility e) {
        boolean preorder = e.preorder() && e.windowId() != null;
        if (preorder && e.deliversOn() == null) {
            preorder = false;
        }
        Set<String> methods = new LinkedHashSet<>();
        if (e.allowedMethods() != null) {
            for (String m : e.allowedMethods()) {
                String v = m == null ? "" : m.trim().toUpperCase(Locale.ROOT);
                if (!v.isEmpty()) {
                    methods.add(v);
                }
            }
        }
        return new Line(e.atin(), preorder, e.windowId(), e.windowCode(), e.windowName(), PreorderStage.parse(e.stage()),
                e.deliversOn(), e.closesAt(), e.door() == null || e.door(), e.pickup() != null && e.pickup(),
                e.doorFeeMinor() == null ? DEFAULT_DOOR_FEE_MINOR : e.doorFeeMinor(),
                e.pickupFeeMinor() == null ? 0 : e.pickupFeeMinor(),
                e.currency() == null || e.currency().isBlank() ? "USD" : e.currency(), methods,
                e.pickupPointId() == null || e.pickupPointId().isBlank() ? null : e.pickupPointId(),
                DeliveryRulesMapping.snapshot(e.doorFeeRule()), DeliveryRulesMapping.snapshot(e.pickupFeeRule()));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
