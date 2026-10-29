package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class CreditRefundServiceImpl implements CreditRefundService {

    private static final int MIN_REASON = 15;

    private final PaymentRepository payments;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    CreditRefundServiceImpl(PaymentRepository payments, MoneyTransactions tx, PaymentsAccess access, Clock clock) {
        this.payments = payments;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Choice chooseAsStaff(String orderId, String reason) {
        Actor staff = access.adjuster();
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why (at least " + MIN_REASON
                    + " characters)", Map.of("reason", "Required"));
        }
        return choose(orderId, staff.subject(), reason.trim());
    }

    private Choice choose(String orderId, String by, String reason) {
        return tx.serializable(() -> {
            Payment found = payments.findByOrderId(orderId).orElseThrow(() -> new NotFoundException(
                    "payments.payment-not-found", "No payment for order " + orderId));
            Payment p = payments.lockById(found.id()).orElseThrow();
            if (owner(p) == null) {
                throw new PaymentsRuleViolation("payments.no-credit-account", "This order was not paid by a "
                        + "signed-in shopper, so there is no credit account to refund to");
            }
            if (!p.cardAmount().isPositive()) {
                throw new PaymentsStateConflict("payments.no-card-portion", "Order " + orderId + " was paid with "
                        + "credit only: its refunds already come back as credit");
            }
            Instant now = Instant.now(clock);
            if (!p.cardRefundsAsCredit()) {
                payments.markCardRefundsAsCredit(p.id(), by, reason, now);
            }
            return new Choice(orderId, true, p.cardAmount(), by, now);
        });
    }

    /** The customer who paid: the shopper whose credit was used, else the order's customer. */
    private static String owner(Payment p) {
        if (p.shopperId() != null && !p.shopperId().isBlank()) {
            return p.shopperId();
        }
        String fromOrder = p.metadata().get("customer_id");
        return fromOrder == null || fromOrder.isBlank() ? p.metadata().get("afrotransact_customer_id") : fromOrder;
    }
}
