package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.payments.domain.PayoutHold;
import com.afrotransact.payments.port.PayoutHoldRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerEnforcementServiceImpl implements SellerEnforcementService {

    static final String CONSUMER = "payments.seller-compliance";

    private static final Logger LOG = LoggerFactory.getLogger(SellerEnforcementServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final PayoutHoldRepository holds;
    private final Clock clock;

    SellerEnforcementServiceImpl(InboxDeduplicator inbox, PayoutHoldRepository holds, Clock clock) {
        this.inbox = inbox;
        this.holds = holds;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean payoutsPaused(EnforcementNotice notice) {
        return apply(notice, true);
    }

    @Override
    @Transactional
    public boolean payoutsResumed(EnforcementNotice notice) {
        return apply(notice, false);
    }

    private boolean apply(EnforcementNotice n, boolean paused) {
        if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
            return false;
        }
        if (n.orgId() == null || n.orgId().isBlank()) {
            LOG.warn("Ignoring payout enforcement event {} without orgId", n.eventId());
            return false;
        }
        Instant now = Instant.now(clock);
        Instant at = n.occurredAt() == null ? now : n.occurredAt();
        String reason = n.reason() == null || n.reason().isBlank() ? (paused ? "MANUAL" : "CLEARED") : n.reason();
        boolean changed = holds.apply(new PayoutHold(n.orgId(), n.accountId(), paused, reason, n.actorId(), n.note(),
                at), now);
        if (changed) {
            LOG.info("Payouts {} for {} ({})", paused ? "paused" : "resumed", n.orgId(), reason);
        } else {
            LOG.info("Stale payout enforcement event {} for {} ignored", n.eventId(), n.orgId());
        }
        return changed;
    }
}
