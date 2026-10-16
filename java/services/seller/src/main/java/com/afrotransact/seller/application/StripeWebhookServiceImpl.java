package com.afrotransact.seller.application;

import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.StripeWebhookParser;
import com.afrotransact.seller.port.WebhookInbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Verifies the signature first (outside any transaction), then claims the event id in the inbox and applies the
 * event in the same transaction, so a crash re-delivers and a duplicate delivery is a no-op. After that transaction
 * commits, plan invoices paid from earnings (S298) are collected at once: a renewal invoice is a draft for an hour
 * after we acknowledge {@code invoice.created}, and a transient failure answers 503 so Stripe redelivers (the
 * redelivery, a duplicate, collects again) and keeps the invoice a draft.
 */
@Service
class StripeWebhookServiceImpl implements StripeWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookServiceImpl.class);

    private final StripeWebhookParser parser;
    private final WebhookInbox inbox;
    private final ComplianceService compliance;
    private final EarningsCollectionService earnings;
    private final TransactionOperations tx;

    StripeWebhookServiceImpl(StripeWebhookParser parser, WebhookInbox inbox, ComplianceService compliance,
                             EarningsCollectionService earnings, TransactionOperations tx) {
        this.parser = parser;
        this.inbox = inbox;
        this.compliance = compliance;
        this.earnings = earnings;
        this.tx = tx;
    }

    @Override
    public WebhookResult receive(StripeWebhookParser.Endpoint endpoint, String payload, String signature) {
        StripeEvent event = parser.parse(endpoint, payload, signature);
        WebhookResult result = tx.execute(s -> {
            if (!inbox.firstDelivery(event.id(), endpoint.name(), event.type(), event.accountId(), event.created())) {
                LOG.debug("Duplicate Stripe event {} ({}) ignored", event.id(), event.type());
                return new WebhookResult(event.id(), event.type(), true, "duplicate");
            }
            String outcome = endpoint == StripeWebhookParser.Endpoint.CONNECT ? compliance.handle(event) : "ignored";
            inbox.outcome(event.id(), outcome);
            return new WebhookResult(event.id(), event.type(), false, outcome);
        });
        if (endpoint != StripeWebhookParser.Endpoint.BILLING || !earnings.enabled()) {
            return result;
        }
        if ("invoice.created".equals(event.type()) && event.invoice() != null) {
            if (earnings.collect(event.invoice().id()) == EarningsCollectionService.Outcome.RETRY) {
                LOG.info("Invoice {}: paying it from sales failed for now; asking Stripe to redeliver",
                        event.invoice().id());
                return result.retry();
            }
        } else if (event.invoice() != null) {
            earnings.release(event.invoice().id());
        } else if (event.creditNote() != null && event.creditNote().invoiceId() != null) {
            earnings.release(event.creditNote().invoiceId());
        }
        return result;
    }
}
