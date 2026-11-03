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
 * event in the same transaction, so a crash re-delivers and a duplicate delivery is a no-op. Connect events go to
 * compliance. The billing endpoint carries only the outcome of plan-fee PaymentIntents (a bank debit that was still
 * processing when we charged): it is applied in the same transaction (database work only).
 */
@Service
class StripeWebhookServiceImpl implements StripeWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookServiceImpl.class);

    private final StripeWebhookParser parser;
    private final WebhookInbox inbox;
    private final ComplianceService compliance;
    private final InvoiceCollectionService collection;
    private final TransactionOperations tx;

    StripeWebhookServiceImpl(StripeWebhookParser parser, WebhookInbox inbox, ComplianceService compliance,
                             InvoiceCollectionService collection, TransactionOperations tx) {
        this.parser = parser;
        this.inbox = inbox;
        this.compliance = compliance;
        this.collection = collection;
        this.tx = tx;
    }

    @Override
    public WebhookResult receive(StripeWebhookParser.Endpoint endpoint, String payload, String signature) {
        StripeEvent event = parser.parse(endpoint, payload, signature);
        return tx.execute(s -> {
            if (!inbox.firstDelivery(event.id(), endpoint.name(), event.type(), event.accountId(), event.created())) {
                LOG.debug("Duplicate Stripe event {} ({}) ignored", event.id(), event.type());
                return new WebhookResult(event.id(), event.type(), true, "duplicate");
            }
            String outcome = "ignored";
            if (endpoint == StripeWebhookParser.Endpoint.CONNECT) {
                outcome = compliance.handle(event);
            } else if (event.paymentIntent() != null) {
                StripeEvent.PaymentIntentResult pi = event.paymentIntent();
                collection.onPaymentIntentResult(pi.id(), pi.succeeded(), pi.failureCode(), pi.failureMessage());
                outcome = "plan-payment";
            }
            inbox.outcome(event.id(), outcome);
            return new WebhookResult(event.id(), event.type(), false, outcome);
        });
    }
}
