package com.afrotransact.orders.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentEventServiceImpl implements PaymentEventService {

    static final String CONSUMER = "orders.payment-events";
    static final String SUCCEEDED = "payments.intent.succeeded";
    static final String FAILED = "payments.intent.failed";
    static final String CANCELED = "payments.intent.canceled";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final CheckoutRequestRepository requests;
    private final CheckoutWorkflows workflows;

    PaymentEventServiceImpl(InboxDeduplicator inbox, CheckoutRequestRepository requests,
                            CheckoutWorkflows workflows) {
        this.inbox = inbox;
        this.requests = requests;
        this.workflows = workflows;
    }

    @Override
    @Transactional
    public boolean onIntentEvent(UUID eventId, String type, String orderId, String failureMessage) {
        if (!SUCCEEDED.equals(type) && !FAILED.equals(type) && !CANCELED.equals(type)) {
            return false;
        }
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        Optional<CheckoutRequestRepository.CheckoutRequest> request = requests.findByOrderId(orderId);
        if (request.isEmpty()) {
            LOG.debug("Payment event {} for unknown order {} (not an orders checkout)", type, orderId);
            return false;
        }
        boolean authorized = SUCCEEDED.equals(type);
        workflows.signalPayment(request.get().workflowId(), authorized, authorized ? null
                : failureMessage == null || failureMessage.isBlank() ? "The payment failed" : failureMessage);
        return true;
    }
}
