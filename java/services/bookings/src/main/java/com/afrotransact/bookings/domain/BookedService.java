package com.afrotransact.bookings.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The service as it was when booked: later price, deposit or duration changes don't touch existing bookings.
 *
 * @param durationMinutes service duration plus add-on time
 * @param priceMinor      service price plus add-ons
 * @param category        the service's category, which selects payments' connection-fee rate ("services" for
 *                        bookings made before it was recorded)
 */
public record BookedService(UUID serviceId, String name, int durationMinutes, long priceMinor, long depositMinor,
                            String currency, List<AddOn> addOns, ServiceMode mode, String providerName,
                            String memberName, String category) {

    /** Category of bookings made before the service's category was recorded. */
    public static final String DEFAULT_CATEGORY = "services";

    public BookedService {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(mode, "mode");
        addOns = addOns == null ? List.of() : List.copyOf(addOns);
        category = category == null || category.isBlank() ? DEFAULT_CATEGORY
                : category.trim().toLowerCase(Locale.ROOT);
    }

    /** A booked service without a recorded category. */
    public BookedService(UUID serviceId, String name, int durationMinutes, long priceMinor, long depositMinor,
                         String currency, List<AddOn> addOns, ServiceMode mode, String providerName,
                         String memberName) {
        this(serviceId, name, durationMinutes, priceMinor, depositMinor, currency, addOns, mode, providerName,
                memberName, null);
    }

    /** Snapshot of {@code offering} with the picked add-ons. */
    public static BookedService of(ServiceOffering offering, List<AddOn> addOns, ServiceMode mode,
                                   String providerName, String memberName) {
        if (!offering.modes().contains(mode)) {
            throw new BookingsRuleViolation("bookings.mode-not-offered", "This service isn't offered "
                    + mode.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        long extraPrice = addOns.stream().mapToLong(AddOn::priceMinor).sum();
        int extraMinutes = addOns.stream().mapToInt(AddOn::extraMinutes).sum();
        return new BookedService(offering.id(), offering.name(), offering.durationMinutes() + extraMinutes,
                offering.priceMinor() + extraPrice, offering.depositMinor(), offering.currency(), addOns, mode,
                providerName, memberName, offering.category());
    }

    public long balanceMinor() {
        return priceMinor - depositMinor;
    }
}
