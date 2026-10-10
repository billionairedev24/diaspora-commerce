package com.afrotransact.bookings.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.ServiceMode;

/** Services &amp; prices of a provider: duration, price, deposit, modes, add-ons, take-home, who performs it. */
public interface ServiceCatalogService {

    ServiceView create(String orgId, ServiceCommand command);

    /** New bookings use the new values; existing bookings keep the price they were made at. */
    ServiceView update(String orgId, UUID serviceId, ServiceCommand command);

    ServiceView setActive(String orgId, UUID serviceId, boolean active);

    /** Team members who perform the service (empty = every bookable member). bookings:team:manage. */
    ServiceView assignMembers(String orgId, UUID serviceId, Set<UUID> memberIds);

    List<ServiceView> list(String orgId);

    ServiceView get(String orgId, UUID serviceId);

    /** Service fields as entered on Services &amp; prices. */
    record ServiceCommand(String name, String description, String category, int durationMinutes, long priceMinor,
                          long depositMinor, Set<ServiceMode> modes, List<AddOn> addOns) {
    }
}
