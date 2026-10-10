package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A chargeback on a payment's charge, allocated to the responsible recipients.
 *
 * @param fee Stripe's dispute fee withdrawn with the amount (AfroTransact's cost)
 */
public record Dispute(String id, UUID paymentId, String orderId, String chargeId, Money amount, Money fee,
                      DisputeStatus status,
                      String reason, List<DisputeAllocation> allocations, Instant openedAt, Instant closedAt) {

    public Dispute {
        allocations = List.copyOf(allocations);
        reason = reason == null ? "unknown" : reason;
    }

    /**
     * Allocates the disputed amount to recipients in proportion to what each was charged (largest remainder,
     * AfroTransact takes ties) — the responsible seller of a single-seller order bears all of it.
     *
     * @param chargedByRecipient captured items + shipping + tax per recipient, in insertion order
     */
    public static List<DisputeAllocation> allocate(Money amount, Map<String, Money> chargedByRecipient) {
        List<String> ids = new ArrayList<>(chargedByRecipient.keySet());
        if (!ids.contains(PaymentSplitter.PLATFORM)) {
            ids.addFirst(PaymentSplitter.PLATFORM);
        }
        long[] weights = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            Money m = chargedByRecipient.get(ids.get(i));
            weights[i] = m == null ? 0 : Math.max(0, m.minor());
        }
        long[] shares = Apportionment.largestRemainder(amount.minor(), weights, ids.indexOf(PaymentSplitter.PLATFORM));
        List<DisputeAllocation> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (shares[i] != 0) {
                out.add(new DisputeAllocation(ids.get(i), Money.of(shares[i], amount.currency())));
            }
        }
        return out;
    }

    /**
     * Allocations borne by marketplace sellers (who have a payable to reserve): neither AfroTransact nor AfroTransact
     * Retail ({@code retailOrgId}).
     */
    public List<DisputeAllocation> sellerAllocations(String retailOrgId) {
        return allocations.stream().filter(a -> !PaymentSplitter.PLATFORM.equals(a.recipientId())
                && !a.recipientId().equals(retailOrgId)).toList();
    }

    public Dispute close(DisputeStatus outcome, Instant at) {
        if (status != DisputeStatus.OPEN) {
            return this;
        }
        return new Dispute(id, paymentId, orderId, chargeId, amount, fee, outcome, reason, allocations, openedAt, at);
    }
}
