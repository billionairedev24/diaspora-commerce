package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/** A Stripe Connect transfer of a seller's matured net for one order ({@code transfer_group} = order id). */
public record SettlementTransfer(UUID id, String orderId, String orgId, String stripeTransferId, Money amount,
                                 Money reversed, Instant createdAt) {

    public Money remaining() {
        return amount.minus(reversed);
    }

    public SettlementTransfer reversedBy(Money more) {
        return new SettlementTransfer(id, orderId, orgId, stripeTransferId, amount, reversed.plus(more), createdAt);
    }
}
