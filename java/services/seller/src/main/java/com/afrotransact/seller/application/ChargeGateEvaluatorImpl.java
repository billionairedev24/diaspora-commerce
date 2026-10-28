package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.SellerMetricRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ChargeGateEvaluatorImpl implements ChargeGateEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(ChargeGateEvaluatorImpl.class);
    private static final int PAGE = 100;

    private final SubscriptionRepository subs;
    private final SellerMetricRepository metrics;
    private final TransactionOperations tx;
    private final Clock clock;

    ChargeGateEvaluatorImpl(SubscriptionRepository subs, SellerMetricRepository metrics, TransactionOperations tx,
                            Clock clock) {
        this.subs = subs;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Optional<ChargeGate.Decision> decide(String orgId, Instant now) {
        ChargeGate gate = subs.terms(orgId).chargeGate();
        if (gate == null || gate.mode() == ChargeGate.Mode.START_AFTER && gate.metAt() != null) {
            return Optional.empty();
        }
        return Optional.of(gate.decide(now, gate.needsMetric() ? progress(orgId, gate, now) : 0));
    }

    @Override
    public void afterIssue(String orgId, ChargeGate.Decision decision, Instant now) {
        if (decision == null) {
            return;
        }
        if (decision.nowMet()) {
            subs.markChargeGateMet(orgId, now);
        } else if (decision.expired()) {
            subs.clearChargeGate(orgId);
        }
    }

    @Override
    public long progress(String orgId, ChargeGate gate, Instant now) {
        if (gate == null || !gate.needsMetric()) {
            return 0;
        }
        if (gate.metric() == RewardMetric.ACTIVE_LISTINGS) {
            return metrics.activeListings(orgId);
        }
        LocalDate from = LocalDate.ofInstant(gate.since() == null ? gate.setAt() : gate.since(), ZoneOffset.UTC);
        return metrics.sum(orgId, gate.metric(), from, LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    public int markMetGates() {
        Instant now = Instant.now(clock);
        int met = 0;
        String after = null;
        while (true) {
            String cursor = after;
            List<Subscription> page = tx.execute(s -> subs.withOpenStartGate(cursor, PAGE));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Subscription sub : page) {
                after = sub.orgId();
                ChargeGate gate = subs.terms(sub.orgId()).chargeGate();
                if (gate != null && gate.mode() == ChargeGate.Mode.START_AFTER && gate.metAt() == null
                        && progress(sub.orgId(), gate, now) >= gate.threshold()) {
                    tx.executeWithoutResult(s -> subs.markChargeGateMet(sub.orgId(), now));
                    LOG.info("Charge gate of {} reached {} {}: billing starts at the next period", sub.orgId(),
                            gate.threshold(), gate.metric());
                    met++;
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return met;
    }
}
