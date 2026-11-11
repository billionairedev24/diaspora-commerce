package com.afrotransact.bookings.domain;

import java.util.List;
import java.util.Optional;

/** The platform's metro zones, looked up by key or by city. */
public record Zones(List<ServiceZone> all) {

    public static final Zones NONE = new Zones(List.of());

    public Zones {
        all = List.copyOf(all);
    }

    public Optional<ServiceZone> zone(String key) {
        return all.stream().filter(z -> z.key().equals(key)).findFirst();
    }

    /** The zone a city belongs to (the first, when zones overlap). */
    public Optional<ServiceZone> ofCity(String city, String state) {
        return all.stream().filter(z -> z.contains(city, state)).findFirst();
    }
}
