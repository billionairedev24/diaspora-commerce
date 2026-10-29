package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A benefit a member used: a delivery fee waived on an order, cashback or monthly credit granted. Together they are
 * the cost of the benefits to AfroTransact, and the "benefits used" the member sees.
 *
 * @param orderId the order the benefit applied to; null for the monthly credit
 * @param ref     the shipment of a waived fee, empty otherwise (a benefit applies once per order and ref)
 */
public record MembershipBenefitUse(UUID id, UUID membershipId, String customerId, String tierCode, BenefitCode benefit,
                                   String orderId, String ref, Money amount, String note, Instant createdAt) {

    public MembershipBenefitUse {
        ref = ref == null ? "" : ref;
    }
}
