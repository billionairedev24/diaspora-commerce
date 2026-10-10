package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.StripeWebhookVerifier;
import com.afrotransact.payments.port.StripeWebhookVerifier.VerifiedEvent;
import com.afrotransact.payments.port.WebhookInbox;
import com.afrotransact.payments.port.WebhookInbox.InboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class WebhookServiceImpl implements WebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookServiceImpl.class);
    private static final int MAX_ERROR = 2000;

    private final StripeWebhookVerifier verifier;
    private final WebhookInbox inbox;
    private final StripeEventHandlers handlers;
    private final MoneyTransactions tx;
    private final PaymentsMetrics metrics;
    private final PaymentsSettings settings;
    private final Clock clock;

    WebhookServiceImpl(StripeWebhookVerifier verifier, WebhookInbox inbox, StripeEventHandlers handlers,
                       MoneyTransactions tx, PaymentsMetrics metrics, PaymentsSettings settings, Clock clock) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.handlers = handlers;
        this.tx = tx;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public WebhookReceipt receive(String payload, String signatureHeader) {
        VerifiedEvent event = verifier.verify(payload, signatureHeader);
        boolean handled = handlers.supports(event.type());
        boolean stored = tx.requiresNew(() -> inbox.store(event, handled, Instant.now(clock)));
        metrics.webhook(event.type(), !stored ? "duplicate" : handled ? "stored" : "ignored");
        return new WebhookReceipt(event.id(), event.type(), !stored, handled);
    }

    @Override
    public int processPending(int max) {
        int attempted = 0;
        while (attempted < max) {
            AtomicReference<InboxEvent> current = new AtomicReference<>();
            try {
                boolean processed = tx.serializable(() -> {
                    Optional<InboxEvent> next = inbox.lockNext(Instant.now(clock));
                    if (next.isEmpty()) {
                        return false;
                    }
                    current.set(next.get());
                    handlers.handle(next.get());
                    inbox.markProcessed(next.get().id(), Instant.now(clock));
                    return true;
                });
                if (!processed) {
                    break;
                }
                metrics.webhook(current.get().type(), "processed");
            } catch (RuntimeException ex) {
                InboxEvent failed = current.get();
                if (failed == null) {
                    throw ex;
                }
                recordFailure(failed, ex);
            }
            attempted++;
        }
        return attempted;
    }

    private void recordFailure(InboxEvent failed, RuntimeException ex) {
        int attempts = failed.attempts() + 1;
        boolean giveUp = attempts >= settings.webhookMaxAttempts();
        Instant next = Instant.now(clock).plus(backoff(attempts));
        String message = String.valueOf(ex.getMessage());
        String error = ex.getClass().getSimpleName() + ": "
                + (message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message);
        tx.requiresNew(() -> {
            inbox.markFailed(failed.id(), error, next, giveUp);
            return null;
        });
        metrics.webhook(failed.type(), giveUp ? "failed" : "retry");
        if (giveUp) {
            LOG.error("Stripe event {} ({}) failed {} times and is parked", failed.id(), failed.type(), attempts, ex);
        } else {
            LOG.warn("Stripe event {} ({}) failed (attempt {}), retrying at {}: {}", failed.id(), failed.type(),
                    attempts, next, error);
        }
    }

    /** 2 s, 4 s, 8 s … capped at 15 minutes. */
    static Duration backoff(int attempts) {
        long seconds = Math.min(900, 1L << Math.min(attempts, 10));
        return Duration.ofSeconds(seconds);
    }
}
