package com.afrotransact.orders.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Window = delivery + the longest window of the shipment's returnable lines: the product type's window when
 * configured, else the default (30 days). Perishable lines and non-returnable product types never open a window
 * (tax-returns offers "report a problem" for them).
 */
public final class DefaultReturnWindowPolicy implements ReturnWindowPolicy {

    private final int defaultDays;
    private final Map<String, Integer> daysByProductType;
    private final Set<String> nonReturnableProductTypes;

    public DefaultReturnWindowPolicy(int defaultDays, Map<String, Integer> daysByProductType,
                                     Set<String> nonReturnableProductTypes) {
        this.defaultDays = defaultDays;
        this.daysByProductType = Map.copyOf(daysByProductType);
        this.nonReturnableProductTypes = Set.copyOf(nonReturnableProductTypes);
    }

    @Override
    public Optional<Instant> windowEndsAt(Shipment s) {
        if (s.status() != ShipmentStatus.DELIVERED || s.deliveredAt() == null) {
            return Optional.empty();
        }
        return s.lines().stream().filter(this::returnable)
                .map(l -> l.productType() == null ? defaultDays
                        : daysByProductType.getOrDefault(l.productType(), defaultDays))
                .max(Integer::compare)
                .map(days -> s.deliveredAt().plus(Duration.ofDays(days)));
    }

    @Override
    public boolean problemReportOnly(Shipment s) {
        return s.status() == ShipmentStatus.DELIVERED && s.lines().stream().noneMatch(this::returnable);
    }

    private boolean returnable(OrderLine l) {
        return !l.perishable() && (l.productType() == null || !nonReturnableProductTypes.contains(l.productType()));
    }
}
