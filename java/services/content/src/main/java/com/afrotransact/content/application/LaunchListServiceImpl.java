package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.newsletter.LaunchAudience;
import com.afrotransact.content.domain.newsletter.LaunchListEntry;
import com.afrotransact.content.port.LaunchListRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LaunchListServiceImpl implements LaunchListService {

    private final LaunchListRepository entries;
    private final MarketSnapshotCache published;
    private final DomainEventPublisher events;
    private final Clock clock;

    LaunchListServiceImpl(LaunchListRepository entries, MarketSnapshotCache published, DomainEventPublisher events,
                          Clock clock) {
        this.entries = entries;
        this.published = published;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LaunchListEntry join(String email, String country, LaunchAudience audience, boolean shoppingUpdates) {
        EmailAddress address = EmailAddress.parse(email, "email");
        Country c = published.get().country(country).orElseThrow(() -> new ContentRuleViolation(
                "content.unknown-country", "Unknown country " + country, Map.of("country", "Unknown country")));
        if (c.live()) {
            throw new ContentRuleViolation("content.country-live", c.name() + " is already live — you can sign up now",
                    Map.of("country", "Already live"));
        }
        LaunchListEntry stored = entries.upsert(new LaunchListEntry(UUID.randomUUID(), address, c.code(),
                audience == null ? LaunchAudience.SELLER : audience, shoppingUpdates, Instant.now(clock)));
        events.publish(ContentEvents.LAUNCH_LIST_JOINED, ContentEvents.AGGREGATE, c.code(),
                new ContentEventPayloads.LaunchListJoined(stored.id(), address.value(), c.code(), c.name(),
                        stored.audience().name(), stored.shoppingUpdates(), c.launchNote()));
        return stored;
    }
}
