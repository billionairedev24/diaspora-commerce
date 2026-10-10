package com.afrotransact.seller.application;

import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.StripeWebhookParser;
import com.afrotransact.seller.port.WebhookInbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the signature first (outside any transaction), then claims the event id in the inbox and applies the
 * event in the same transaction, so a crash re-delivers and a duplicate delivery is a no-op.
 */
@Service
class StripeWebhookServiceImpl implements StripeWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookServiceImpl.class);

    private final StripeWebhookParser parser;
    private final WebhookInbox inbox;
    private final ComplianceService compliance;
    private final BillingService billing;

    StripeWebhookServiceImpl(StripeWebhookParser parser, WebhookInbox inbox, ComplianceService compliance,
                             BillingService billing) {
        this.parser = parser;
        this.inbox = inbox;
        this.compliance = compliance;
        this.billing = billing;
    }

    @Override
    @Transactional
    public WebhookResult receive(StripeWebhookParser.Endpoint endpoint, String payload, String signature) {
        StripeEvent event = parser.parse(endpoint, payload, signature);
        if (!inbox.firstDelivery(event.id(), endpoint.name(), event.type(), event.accountId(), event.created())) {
            LOG.debug("Duplicate Stripe event {} ({}) ignored", event.id(), event.type());
            return new WebhookResult(event.id(), event.type(), true, "duplicate");
        }
        String outcome = endpoint == StripeWebhookParser.Endpoint.CONNECT ? compliance.handle(event)
                : billing.handle(event);
        inbox.outcome(event.id(), outcome);
        return new WebhookResult(event.id(), event.type(), false, outcome);
    }
}
