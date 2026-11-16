package com.afrotransact.orders.adapter.out.module;

import java.util.List;
import java.util.Objects;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link DeliveryRunSlots} through delivery-runs' published {@link DeliveryRunsApi} inside this JVM (mode {@code in-process}), entered as
 * orders' caller principal (scope {@code delivery-runs:reserve}; the open-run read is public). Same outcomes as the HTTP adapter: a
 * run that is closed or full is {@code RunUnavailable} ({@code orders.run-closed} / {@code orders.run-full}), releasing a slot the order
 * does not hold is fine (404), runs with no cutoff or day are skipped, and a reservation asks for exactly the run quoted.
 * delivery-runs is switched off in core by default, in which case the HTTP adapter is the one that is selected.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "delivery-runs", value = ModuleMode.IN_PROCESS)
class InProcessDeliveryRunSlots implements DeliveryRunSlots {

    private static final String SERVICE = "delivery-runs";

    private final ObjectProvider<DeliveryRunsApi> api;
    private final ModuleUpstream upstream;

    InProcessDeliveryRunSlots(ObjectProvider<DeliveryRunsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(DeliveryRunsApi.class, true, false).length > 0, "orders",
                "delivery-runs", "at.orders.deliveryruns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public List<OpenRun> openRuns(String zip) {
        return openRuns(zip, 0);
    }

    @Override
    public List<OpenRun> openRuns(String zip, int earlyAccessHours) {
        return upstream.call(SERVICE, () -> api.getObject().openRuns(zip, earlyAccessHours)).stream()
                .filter(r -> r.runId() != null && r.cutoffAt() != null && r.deliversOn() != null)
                .map(InProcessDeliveryRunSlots::run).filter(Objects::nonNull).toList();
    }

    @Override
    public void reserve(String runId, String orderId, String customerId) {
        reserve(runId, orderId, customerId, 0, null);
    }

    @Override
    public void reserve(String runId, String orderId, String customerId, int earlyAccessHours) {
        reserve(runId, orderId, customerId, earlyAccessHours, null);
    }

    @Override
    public void reserve(String runId, String orderId, String customerId, int earlyAccessHours, RunCharge charge) {
        DeliveryRunsApi.RunCharge sent = charge == null ? null
                : new DeliveryRunsApi.RunCharge(charge.feeMinor(), charge.totalMinor(), charge.kind());
        try {
            upstream.run(SERVICE, () -> api.getObject().reserve(runId, orderId, customerId, earlyAccessHours, sent));
        } catch (UpstreamFailure f) {
            if (f.status() == 409 && "delivery-runs.run-full".equals(f.code())) {
                throw new RunUnavailable("orders.run-full", "This delivery run is full");
            }
            if (f.status() == 409 && "delivery-runs.run-closed".equals(f.code())) {
                throw new RunUnavailable("orders.run-closed", "This delivery run has closed for orders");
            }
            throw f;
        }
    }

    @Override
    public void release(String runId, String orderId) {
        try {
            upstream.run(SERVICE, () -> api.getObject().release(runId, orderId));
        } catch (UpstreamFailure f) {
            if (f.status() != 404) {
                throw f;
            }
        }
    }

    private static OpenRun run(DeliveryRunsApi.OpenRun r) {
        boolean pickup = "PICKUP_POINT".equalsIgnoreCase(r.dropMode());
        return new OpenRun(r.runId(), r.town(), r.cutoffAt(), r.deliversOn(), r.timeZone(), r.feeMinor(),
                r.currency() == null ? "USD" : r.currency(), !pickup, pickup, null, DeliveryRulesMapping.snapshot(r.feeRule()),
                r.minOrders(), r.ordersSoFar(), r.full());
    }
}
