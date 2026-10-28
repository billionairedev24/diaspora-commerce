package com.afrotransact.orders.port;

import java.util.List;
import java.util.Optional;

/**
 * payments {@code POST /v1/memberships/waivers} with the orders-service token ({@code payments:service}): which of an
 * order's delivery fees the customer's membership waives (S323, ADR 0006 section 4). The fee rules of ADR 0005 price the
 * fees; the membership then waives some of them, and payments is the one place that rule lives.
 */
public interface MemberBenefits {

    /**
     * The waivers for an order. Empty when the customer is not a member, no tier covers the fees, or payments cannot
     * answer (a checkout never fails because of a loyalty benefit; the customer pays the fee).
     *
     * @param itemsMinor the goods subtotal of the whole order
     */
    Optional<Waivers> waivers(String customerId, long itemsMinor, String currency, List<Fee> fees);

    /**
     * How many hours before the public opening the customer's membership lets them order from pre-order windows and
     * delivery runs (S323 early access); 0 for a non-member or when payments cannot answer.
     */
    int earlyAccessHours(String customerId);

    /** What kind of delivery fee it is: only AfroTransact delivery and carrier shipping can be waived. */
    enum FeeKind { AFROTRANSACT_DELIVERY, CARRIER, PICKUP, EXPRESS }

    /** A fee the delivery fee rules priced; {@code key} is the shipment number within the order. */
    record Fee(String key, FeeKind kind, long feeMinor) {
    }

    /** A fee waived: the shipment, the benefit (FREE_DELIVERY or FREE_SHIPPING) and the amount. */
    record Waiver(String key, String benefit, long waivedMinor) {
    }

    /** The member's tier and what it waived on this order. */
    record Waivers(String tierCode, String tierName, List<Waiver> waivers) {

        public Waivers {
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }
    }
}
