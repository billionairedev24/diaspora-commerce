package com.afrotransact.payments.adapter.in.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

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
    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final int webhookBatch;
    private final int settlementBatch;
    private final ZoneId zone;

    PaymentsJobs(WebhookService webhooks, SettlementService settlements, ReconciliationService reconciliation,
                 Clock clock, @Value("${at.payments.jobs.webhook-batch:100}") int webhookBatch,
                 @Value("${at.payments.jobs.settlement-batch:200}") int settlementBatch,
                 @Value("${at.payments.zone:UTC}") String zone) {
        this.webhooks = webhooks;
        this.settlements = settlements;
        this.reconciliation = reconciliation;
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
    }

    @Scheduled(cron = "${at.payments.jobs.reconciliation-cron:0 10 3 * * *}", zone = "${at.payments.zone:UTC}")
    void reconcileYesterday() {
        run("reconciliation", () -> reconciliation.runDaily(LocalDate.now(clock.withZone(zone)).minusDays(1)));
    }

    private static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.error("Payments job {} failed; it will run again on schedule", job, e);
        }
    }
}
