package com.afrotransact.fulfilment.adapter.out.module;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.port.DeliveryRunsClient;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link DeliveryRunsClient} through delivery-runs' published {@link DeliveryRunsApi#dispatch} inside this JVM (mode
 * {@code in-process}), entered as fulfilment's caller principal (scope {@code delivery-runs:read}). Same plan as the HTTP client: only
 * CONFIRMED orders, the zone defaulting to America/Chicago, the hub only when delivery-runs knows it; an answer without a run is the
 * retryable {@code delivery-runs.invalid-response}. delivery-runs is switched off in core by default, in which case the HTTP client
 * is the one selected.
 */
@Component
@ConditionalOnModuleMode(caller = "fulfilment", callee = "delivery-runs", value = ModuleMode.IN_PROCESS)
class InProcessDeliveryRunsClient implements DeliveryRunsClient {

    private static final String SERVICE = "delivery-runs";

    private final ObjectProvider<DeliveryRunsApi> api;
    private final ModuleUpstream upstream;

    InProcessDeliveryRunsClient(ObjectProvider<DeliveryRunsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(DeliveryRunsApi.class, true, false).length > 0, "fulfilment",
                "delivery-runs", "at.fulfilment.deliveryruns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public RunPlan dispatch(String runId) {
        DeliveryRunsApi.RunPlan d = upstream.call(SERVICE, () -> api.getObject().dispatch(runId));
        if (d == null || d.runId() == null || d.runId().isEmpty()) {
            throw new UpstreamFailure(SERVICE, 0, "delivery-runs.invalid-response", "delivery-runs sent no run for " + runId, true,
                    null);
        }
        DeliveryRunsApi.PickupPoint p = d.pickupPoint();
        RunPickupPoint point = p != null && p.name() != null && !p.name().isEmpty()
                ? new RunPickupPoint(p.name(), blank(p.line1()), blank(p.city()), blank(p.state()), blank(p.postalCode()),
                        blank(p.hours()), blank(p.notes())) : null;
        GeoPoint origin = d.originLat() != null && d.originLng() != null ? new GeoPoint(d.originLat(), d.originLng()) : null;
        return new RunPlan(d.runId(), d.status(), blank(d.town()), blank(d.state()),
                blank(d.timeZone()) == null ? "America/Chicago" : d.timeZone(), d.windowStart(), d.windowEnd(), d.sellerPickupAt(),
                d.doorstep(), point, origin, d.confirmedOrders());
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
