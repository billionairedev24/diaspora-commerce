package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.orders.port.DeliveryQuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class DeliveryQuoteSweepServiceImpl implements DeliveryQuoteSweepService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryQuoteSweepServiceImpl.class);
    private static final int BATCH = 500;

    private final DeliveryQuoteRepository quotes;
    private final DeliveryQuoteDeskService desk;
    private final Clock clock;

    DeliveryQuoteSweepServiceImpl(DeliveryQuoteRepository quotes, DeliveryQuoteDeskService desk, Clock clock) {
        this.quotes = quotes;
        this.desk = desk;
        this.clock = clock;
    }

    @Override
    public int escalate() {
        Instant now = Instant.now(clock);
        int raised = 0;
        for (String id : quotes.escalationCandidates(BATCH)) {
            try {
                // One transaction per request under its row lock: replicas running at once escalate once.
                if (desk.escalateIfDue(id, now)) {
                    raised++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Escalation check of delivery quote {} failed; retried on the next run", id, e);
            }
        }
        return raised;
    }

    @Override
    public int expire() {
        Instant now = Instant.now(clock);
        int expired = 0;
        for (String id : quotes.expiredBefore(now, BATCH)) {
            try {
                if (desk.expireIfDue(id, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Expiring delivery quote {} failed; retried on the next run", id, e);
            }
        }
        return expired;
    }
}
