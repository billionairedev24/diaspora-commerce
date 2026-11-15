package com.afrotransact.taxreturns.adapter.out.module;

import java.util.Optional;

import com.afrotransact.fulfilment.api.ReturnPickupApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.port.ReturnPickupScheduler;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ReturnPickupScheduler} through fulfilment's published {@link ReturnPickupApi} inside this JVM (mode {@code in-process}):
 * the same pickups as {@code POST /v1/return-pickups}, {@code GET /v1/return-pickups/{id}} and
 * {@code POST /v1/return-pickups/{id}/cancel}, entered as tax-returns' caller principal. Failures are the HTTP adapter's
 * {@code UpstreamException}s (status and problem code of the refusal).
 */
@Component
@ConditionalOnModuleMode(caller = "tax-returns", callee = "fulfilment", value = ModuleMode.IN_PROCESS)
class InProcessReturnPickupScheduler implements ReturnPickupScheduler {

    private final ModuleUpstreams<ReturnPickupApi> fulfilment;

    InProcessReturnPickupScheduler(ObjectProvider<ReturnPickupApi> api, ObjectProvider<CallerPrincipals> callers,
                                   ListableBeanFactory beans) {
        this.fulfilment = new ModuleUpstreams<>(ReturnPickupApi.class, "fulfilment", api, callers, beans);
    }

    @Override
    public String schedule(PickupRequest r) {
        ReturnAddress a = r.address();
        ReturnPickupApi.PickupRequest request = new ReturnPickupApi.PickupRequest(r.rmaId().toString(), r.rmaNumber(),
                r.orderId().toString(), r.orderNumber(), r.shipmentId().toString(), r.customerId(), r.sellerOrgId(),
                new ReturnPickupApi.Address(blank(a.name()), blank(a.phone()), blank(a.line1()), blank(a.line2()),
                        blank(a.city()), blank(a.state()), blank(a.postalCode()), blank(a.country())),
                blank(r.depotNodeId()), r.readyFrom(), r.collectBy(), Math.max(1, r.units()));
        String id = fulfilment.call(api -> api.schedule(request));
        if (id == null || id.isEmpty()) {
            throw new UpstreamException("fulfilment", 0, null, "fulfilment returned no pickup id", null);
        }
        return id;
    }

    @Override
    public Optional<String> assignedDriver(String pickupId) {
        Optional<String> driver = fulfilment.call(api -> api.assignedDriver(pickupId));
        return driver.filter(d -> !d.isBlank());
    }

    @Override
    public void cancel(String pickupId, String reason) {
        String why = reason == null ? "Return cancelled" : reason;
        fulfilment.call(api -> {
            api.cancel(pickupId, why);
            return null;
        });
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
