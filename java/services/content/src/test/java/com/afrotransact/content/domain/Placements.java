package com.afrotransact.content.domain;

import java.time.Instant;
import java.util.Set;

import com.afrotransact.content.domain.placement.Audience;
import com.afrotransact.content.domain.placement.Device;
import com.afrotransact.content.domain.placement.FrequencyCap;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.Placement.PlacementContent;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Schedule;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.domain.placement.Sponsorship;
import com.afrotransact.content.domain.placement.Targeting;

/** Test fixtures for placements. */
final class Placements {

    static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private Placements() {
    }

    static PlacementContent content(Targeting targeting, Schedule schedule, FrequencyCap cap, int priority, boolean paid) {
        return new PlacementContent("Test", "{\"text\":\"hi\"}", targeting, schedule, cap, priority,
                paid ? new Sponsorship(true, "Kingston Kitchen") : Sponsorship.none());
    }

    static Placement live(String id, SlotKey slot, Targeting targeting, int priority) {
        return live(id, slot, targeting, priority, FrequencyCap.unlimited(), window(-1, null));
    }

    static Placement live(String id, SlotKey slot, Targeting targeting, int priority, FrequencyCap cap, Schedule s) {
        return new Placement(id, slot, "P " + id, "{}", targeting, s, cap, priority, Sponsorship.none(),
                PlacementStatus.LIVE, "author", "author", "approver", null, 0, NOW, NOW);
    }

    /** Window from NOW + startHours to NOW + endHours (null = open-ended). */
    static Schedule window(int startHours, Integer endHours) {
        return new Schedule(NOW.plusSeconds(startHours * 3600L), endHours == null ? null : NOW.plusSeconds(endHours * 3600L));
    }

    static Targeting targeting(Set<String> markets, Set<String> zips, Set<Device> devices, Audience audience) {
        return new Targeting(markets, Set.of(), zips, Set.of(), devices, audience, Set.of());
    }
}
