package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.port.PlacementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlacementLifecycleServiceImpl implements PlacementLifecycleService {

    private static final String SYSTEM = "system:placement-lifecycle";

    private final PlacementRepository placements;
    private final PlacementEvents events;
    private final PlacementCache cache;
    private final AuditTrail audit;
    private final Clock clock;

    PlacementLifecycleServiceImpl(PlacementRepository placements, PlacementEvents events, PlacementCache cache,
                                  AuditTrail audit, Clock clock) {
        this.placements = placements;
        this.events = events;
        this.cache = cache;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int advance() {
        Instant now = Instant.now(clock);
        int changed = 0;
        for (Placement p : placements.findByStatus(List.of(PlacementStatus.SCHEDULED, PlacementStatus.LIVE))) {
            Placement next = null;
            if (p.dueForEnd(now)) {
                next = p.end(now);
            } else if (p.dueForActivation(now)) {
                next = p.activate(now);
            }
            if (next != null && placements.update(next)) {
                audit.record(SYSTEM, "placement." + next.status().name().toLowerCase(Locale.ROOT), "placement",
                        p.id(), Map.of("from", p.status().name()));
                events.published(next, next.status().name());
                changed++;
            }
        }
        if (changed > 0) {
            cache.invalidate();
        }
        return changed;
    }
}
