package com.afrotransact.fulfilment.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.api.ReturnPickupApi;
import com.afrotransact.fulfilment.application.ReturnPickupService;
import com.afrotransact.fulfilment.application.ScheduleReturnPickupCommand;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ReturnPickupApi}: the use cases behind {@code POST /v1/return-pickups},
 * {@code GET /v1/return-pickups/{id}} and {@code POST /v1/return-pickups/{id}/cancel}, entered under the calling module's
 * principal. Over HTTP these endpoints admit staff and service principals only ({@code @StaffOrServiceOnly}) and the service
 * applies no further permission; the caller here is a service principal, so the same holds. Failures leave with the status
 * {@code FulfilmentErrorAdvice} gives them.
 */
@Component
class ReturnPickupApiAdapter implements ReturnPickupApi {

    private final ReturnPickupService pickups;
    private final ModuleEntry entry;

    ReturnPickupApiAdapter(ReturnPickupService pickups, ActorResolver actors) {
        this.pickups = pickups;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public String schedule(PickupRequest r) {
        return entry.run(() -> pickups.schedule(new ScheduleReturnPickupCommand(r.rmaId(), r.rmaNumber(), r.orderId(),
                r.orderNumber(), r.shipmentId(), r.customerId(), r.sellerOrgId(), new ScheduleReturnPickupCommand.Address(
                r.address().name(), r.address().phone(), r.address().line1(), r.address().line2(), r.address().city(),
                r.address().state(), r.address().postalCode(), r.address().country(), null, null), r.depotNodeId(),
                r.readyFrom(), r.collectBy(), r.units())).pickup().id(), ReturnPickupApiAdapter::failure);
    }

    @Override
    public Optional<String> assignedDriver(String pickupId) {
        return entry.run(() -> {
            pickups.get(pickupId);
            return pickups.assignedDriver(pickupId);
        }, ReturnPickupApiAdapter::failure);
    }

    @Override
    public void cancel(String pickupId, String reason) {
        entry.runVoid(() -> pickups.cancel(pickupId, reason), ReturnPickupApiAdapter::failure);
    }

    private static Optional<ModuleApiException> failure(RuntimeException ex) {
        if (ex instanceof FulfilmentRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof FulfilmentConflict conflict) {
            return Optional.of(new ModuleApiException(409, conflict.code(), ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
