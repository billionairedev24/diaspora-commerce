package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a customer's append-only credit ledger. The balance is the sum of {@code amountMinor}.
 *
 * @param amountMinor signed: positive adds credit, negative spends it
 * @param actor       who did it: the customer, a staff member, or the service that granted it
 * @param orderId     the order that spent or got back credit, if any
 * @param reference   the gift card, adjustment or refund behind the entry, if any
 */
public record CreditEntry(UUID id, String customerId, CreditKind kind, long amountMinor, String currency,
                          CreditSource source, String reason, String actor, String orderId, String reference,
                          String idempotencyKey, long balanceAfterMinor, Instant createdAt) {

    public CreditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        if (amountMinor == 0) {
            throw new PaymentsRuleViolation("payments.credit-amount", "A credit entry moves some amount");
        }
        boolean signOk = switch (kind) {
            case GRANT, REFUND -> amountMinor > 0;
            case SPEND -> amountMinor < 0;
            case ADJUSTMENT -> true;
        };
        if (!signOk) {
            throw new PaymentsRuleViolation("payments.credit-amount", kind + " entries have the wrong sign");
        }
        if (reason == null || reason.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "Every credit entry has a reason");
        }
        Objects.requireNonNull(actor, "actor");
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }
}
