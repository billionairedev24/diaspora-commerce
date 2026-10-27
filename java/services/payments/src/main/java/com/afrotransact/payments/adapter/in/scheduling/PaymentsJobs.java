package com.afrotransact.payments.adapter.in.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import com.afrotransact.payments.application.GiftCardService;
import com.afrotransact.payments.application.NegotiatedRateService;
import com.afrotransact.payments.application.ProviderFeeService;
import com.afrotransact.payments.application.ReconciliationService;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.application.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled inbound adapter. Safe on several replicas: the inbox and the settlement job lock rows with
 * {@code FOR UPDATE SKIP LOCKED}; every handler is idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "at.payments.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class PaymentsJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsJobs.class);

    private final WebhookService webhooks;
    private final SettlementService settlements;
    private final ProviderFeeService providerFees;
    private final ReconciliationService reconciliation;
    private final NegotiatedRateService negotiatedRates;
    private final GiftCardService giftCards;
    private final Clock clock;
    private final int webhookBatch;
    private final int settlementBatch;
    private final ZoneId zone;

    PaymentsJobs(WebhookService webhooks, SettlementService settlements, ProviderFeeService providerFees,
                 ReconciliationService reconciliation, NegotiatedRateService negotiatedRates, GiftCardService giftCards,
                 Clock clock,
                 @Value("${at.payments.jobs.webhook-batch:100}") int webhookBatch,
                 @Value("${at.payments.jobs.settlement-batch:200}") int settlementBatch,
                 @Value("${at.payments.zone:UTC}") String zone) {
        this.webhooks = webhooks;
        this.settlements = settlements;
        this.providerFees = providerFees;
        this.reconciliation = reconciliation;
        this.negotiatedRates = negotiatedRates;
        this.giftCards = giftCards;
        this.clock = clock;
        this.webhookBatch = webhookBatch;
        this.settlementBatch = settlementBatch;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(fixedDelayString = "${at.payments.jobs.webhook-interval:1s}")
    void processWebhooks() {
        run("webhook-inbox", () -> webhooks.processPending(webhookBatch));
    }

    @Scheduled(cron = "${at.payments.jobs.settlement-cron:0 */15 * * * *}")
    void releaseMaturedSettlements() {
        run("settlement-transfers", () -> {
            int created = settlements.releaseMatured(settlementBatch);
            if (created > 0) {
                LOG.info("Created {} settlement transfers", created);
            }
        });
        // New transfers fund connected accounts: retry provider fees that could not be debited yet.
        run("provider-fees", () -> {
            int collected = providerFees.collectOutstanding(settlementBatch);
            if (collected > 0) {
                LOG.info("Collected {} outstanding provider fees", collected);
            }
        });
    }

    @Scheduled(cron = "${at.payments.jobs.reconciliation-cron:0 10 3 * * *}", zone = "${at.payments.zone:UTC}")
    void reconcileYesterday() {
        run("reconciliation", () -> reconciliation.runDaily(LocalDate.now(clock.withZone(zone)).minusDays(1)));
    }

    /** Records the expiry of negotiated rates that reached their end date (they stop applying at it anyway). */
    @Scheduled(cron = "${at.payments.jobs.negotiated-rate-expiry-cron:0 */5 * * * *}")
    void expireNegotiatedRates() {
        run("negotiated-rate-expiry", () -> {
            int expired = negotiatedRates.expireDue();
            if (expired > 0) {
                LOG.info("Expired {} negotiated rates", expired);
            }
        });
    }

    /** Emails the gift cards whose send date has come (S321); the code is generated at this moment. */
    @Scheduled(fixedDelayString = "${at.payments.jobs.gift-card-interval:30s}")
    void deliverGiftCards() {
        run("gift-card-delivery", () -> {
            int sent = giftCards.deliverDue();
            if (sent > 0) {
                LOG.info("Handed {} gift cards to notifications", sent);
            }
        });
    }

    private static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.error("Payments job {} failed; it will run again on schedule", job, e);
        }
    }
}
