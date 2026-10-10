package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@code content.placements.published} once per affected market so the edge can purge bundles. */
@Component
public class PlacementEvents {

    private final DomainEventPublisher events;
    private final Clock clock;

    PlacementEvents(DomainEventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void published(Placement p, String action) {
        List<String> markets = p.targeting().markets().isEmpty() ? List.of("*") : List.copyOf(p.targeting().markets());
        for (String market : markets) {
            List<String> paths = new ArrayList<>();
            paths.add("/v1/placements/bundles/" + market);
            paths.add("/v1/placements/resolve?market=" + market);
            List<String> tags = List.of("placements:" + market, "placements:slot:" + p.slot().key());
            events.publish(ContentEvents.PLACEMENTS_PUBLISHED, ContentEvents.AGGREGATE, market,
                    new ContentEventPayloads.PlacementsPublished(p.id(), p.slot().key(), market, p.status().name(),
                            action, paths, tags, Instant.now(clock)));
        }
    }
}
