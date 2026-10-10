package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerComplianceIngestImpl implements SellerComplianceIngest {

    private static final Logger LOG = LoggerFactory.getLogger(SellerComplianceIngestImpl.class);

    private final InboxDeduplicator inbox;
    private final OrgVisibilityRepository visibility;
    private final Clock clock;

    SellerComplianceIngestImpl(InboxDeduplicator inbox, OrgVisibilityRepository visibility, Clock clock) {
        this.inbox = inbox;
        this.visibility = visibility;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean handle(UUID eventId, String eventType, String orgId, String reason, Instant occurredAt) {
        boolean hide = LISTINGS_HIDDEN.equals(eventType);
        if (!hide && !LISTINGS_RESTORED.equals(eventType) || orgId == null || orgId.isBlank()) {
            return false;
        }
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            LOG.debug("Duplicate seller compliance event {} ignored", eventId);
            return false;
        }
        boolean changed = visibility.apply(orgId, hide, reason, occurredAt == null ? Instant.now(clock) : occurredAt);
        LOG.info("Provider {} services {} ({})", orgId, hide ? "hidden" : "restored", reason);
        return changed;
    }
}
