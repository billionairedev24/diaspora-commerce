package com.afrotransact.deliveryruns.it;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationCommand;
import com.afrotransact.deliveryruns.application.RunCommand;
import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PickupPoint;

/** Bastrop-style locations and runs relative to the test clock. */
final class Fixtures {

    static final PickupPoint LIBRARY = new PickupPoint("Bastrop Public Library", "1100 Church St", "Bastrop", "TX",
            "78602", "Sat 2–5 PM", "Car park by the side entrance");

    private Fixtures() {
    }

    static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Radius location around Bastrop (12 miles), minimum 2, at most 3 orders by default. */
    static LocationCommand bastrop(String slug, DropMode mode, Integer capacity, CutoffPolicy policy) {
        return new LocationCommand(slug, "Bastrop", "us", "tx", "America/Chicago", AreaKind.RADIUS, null, null,
                30.1105, -97.3153, 12.0, null, null, 799L, "USD", 2, capacity, null, mode,
                mode == DropMode.PICKUP_POINT ? LIBRARY : null, "https://chat.whatsapp.com/BastropRuns",
                Map.of("facebook", "https://www.facebook.com/groups/bastrop-afro"), policy, 7, true);
    }

    /** A doorstep radius location named {@code name} in Texas (the fake geo puts every one 32 road miles from the hub). */
    static LocationCommand town(String slug, String name) {
        return new LocationCommand(slug, name, "us", "tx", "America/Chicago", AreaKind.RADIUS, null, null,
                30.1105, -97.3153, 12.0, null, null, 799L, "USD", 2, null, null, DropMode.DOORSTEP, null, null,
                Map.of(), CutoffPolicy.CANCEL, 7, true);
    }

    /** Polygon location (geo zone registered from GeoJSON) around Elgin. */
    static LocationCommand elgin(String slug) {
        return new LocationCommand(slug, "Elgin", "US", "TX", "America/Chicago", AreaKind.POLYGON, null,
                "{\"type\":\"Polygon\",\"coordinates\":[[[-97.42,30.30],[-97.32,30.30],[-97.32,30.40],"
                        + "[-97.42,30.40],[-97.42,30.30]]]}",
                30.3494, -97.3703, null, null, null, 899L, "USD", 1, null, null, DropMode.DOORSTEP, null, null,
                null, CutoffPolicy.CANCEL, 3, true);
    }

    /** Cutoff {@code cutoffIn} from now, window two days after the cutoff. */
    static RunCommand run(UUID locationId, Instant now, Duration cutoffIn) {
        return run(locationId, now, cutoffIn, null);
    }

    static RunCommand run(UUID locationId, Instant now, Duration cutoffIn, Integer minOrders) {
        Instant cutoff = now.plus(cutoffIn);
        Instant start = cutoff.plus(Duration.ofDays(2));
        return new RunCommand(locationId, start, start.plus(Duration.ofHours(4)), cutoff, null,
                cutoff.plus(Duration.ofHours(12)), null, null, minOrders, null, null, null, null, null, null);
    }
}
