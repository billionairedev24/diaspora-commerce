package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/** A town or area served by community delivery runs (e.g. Bastrop, about an hour from Austin). */
public record Location(UUID id, LocationDetails details, Instant createdAt, Instant updatedAt, long version) {

    public static Location create(UUID id, LocationDetails details, Instant now) {
        return new Location(id, details, now, now, 0);
    }

    public Location update(LocationDetails changed, Instant now) {
        return new Location(id, changed, createdAt, now, version);
    }

    public Location deactivate(Instant now) {
        LocationDetails d = details;
        return update(new LocationDetails(d.slug(), d.name(), d.market(), d.state(), d.timeZone(), d.area(), d.origin(),
                d.defaultFeeMinor(), d.currency(), d.defaultMinOrders(), d.defaultCapacity(), d.dropMode(),
                d.pickupPoint(), d.whatsappInviteUrl(), d.socialLinks(), d.defaultCutoffPolicy(),
                d.defaultExtendDays(), false), now);
    }

    public Location activate(Instant now) {
        LocationDetails d = details;
        return update(new LocationDetails(d.slug(), d.name(), d.market(), d.state(), d.timeZone(), d.area(), d.origin(),
                d.defaultFeeMinor(), d.currency(), d.defaultMinOrders(), d.defaultCapacity(), d.dropMode(),
                d.pickupPoint(), d.whatsappInviteUrl(), d.socialLinks(), d.defaultCutoffPolicy(),
                d.defaultExtendDays(), true), now);
    }

    public String name() {
        return details.name();
    }

    public String slug() {
        return details.slug();
    }

    public boolean active() {
        return details.active();
    }
}
