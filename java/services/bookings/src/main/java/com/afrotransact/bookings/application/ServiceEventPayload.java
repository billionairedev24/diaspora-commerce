package com.afrotransact.bookings.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;

/** Payload of {@code booking.service.upserted}: what search needs to index a service. */
public record ServiceEventPayload(UUID serviceId, String orgId, String name, String description, String category,
                                  int durationMinutes, long priceMinor, long depositMinor, String currency,
                                  Set<ServiceMode> modes, List<String> addOns, boolean active) {

    static ServiceEventPayload of(ServiceOffering o) {
        return new ServiceEventPayload(o.id(), o.orgId(), o.name(), o.description(), o.category(),
                o.durationMinutes(), o.priceMinor(), o.depositMinor(), o.currency(), o.modes(),
                o.addOns().stream().map(a -> a.name()).toList(), o.active());
    }
}
