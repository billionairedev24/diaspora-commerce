package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.port.SellerMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class SellerMetricsServiceImpl implements SellerMetricsService {

    static final String CONSUMER = "seller.reward-metrics";

    private static final Logger LOG = LoggerFactory.getLogger(SellerMetricsServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final SellerMetricRepository metrics;
    private final RewardService rewards;
    private final TransactionOperations tx;
    private final SellerSettings settings;
    private final Clock clock;

    SellerMetricsServiceImpl(InboxDeduplicator inbox, SellerMetricRepository metrics, RewardService rewards,
                             TransactionOperations tx, SellerSettings settings, Clock clock) {
        this.inbox = inbox;
        this.metrics = metrics;
        this.rewards = rewards;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean shipmentDelivered(UUID eventId, String orgId, long totalMinor, String currency, Instant at) {
        if (skip(orgId)) {
            return false;
        }
        LocalDate day = day(at);
        boolean recorded = Boolean.TRUE.equals(tx.execute(s -> {
            if (!inbox.firstDelivery(CONSUMER, eventId)) {
                return false;
            }
            metrics.add(orgId, RewardMetric.COMPLETED_ORDERS, day, 1);
            if (currency == null || "USD".equalsIgnoreCase(currency)) {
                metrics.add(orgId, RewardMetric.GROSS_SALES, day, Math.max(0, totalMinor));
            }
            return true;
        }));
        if (recorded) {
            evaluate(orgId, RewardMetric.GROSS_SALES);
            evaluate(orgId, RewardMetric.COMPLETED_ORDERS);
        }
        return recorded;
    }

    @Override
    public boolean bookingCompleted(UUID eventId, String orgId, Instant at) {
        if (skip(orgId)) {
            return false;
        }
        boolean recorded = Boolean.TRUE.equals(tx.execute(s -> {
            if (!inbox.firstDelivery(CONSUMER, eventId)) {
                return false;
            }
            metrics.add(orgId, RewardMetric.COMPLETED_BOOKINGS, day(at), 1);
            return true;
        }));
        if (recorded) {
            evaluate(orgId, RewardMetric.COMPLETED_BOOKINGS);
        }
        return recorded;
    }

    @Override
    public boolean offerChanged(UUID eventId, String offerId, String orgId, boolean live, Instant at) {
        if (skip(orgId) || offerId == null) {
            return false;
        }
        boolean changed = Boolean.TRUE.equals(tx.execute(s -> inbox.firstDelivery(CONSUMER, eventId)
                && metrics.setListing(offerId, orgId, live, at == null ? Instant.now(clock) : at)));
        if (changed && live) {
            evaluate(orgId, RewardMetric.ACTIVE_LISTINGS);
        }
        return changed;
    }

    /** Rewards follow the metric; a failure here never loses the metric (the daily job evaluates again). */
    private void evaluate(String orgId, RewardMetric metric) {
        try {
            rewards.evaluate(orgId, metric);
        } catch (RuntimeException e) {
            LOG.warn("Reward evaluation of {} on {} failed; the daily run retries", orgId, metric, e);
        }
    }

    private boolean skip(String orgId) {
        return orgId == null || orgId.isBlank() || settings.isRetail(orgId);
    }

    private LocalDate day(Instant at) {
        return LocalDate.ofInstant(at == null ? Instant.now(clock) : at, ZoneOffset.UTC);
    }
}
