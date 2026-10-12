package com.afrotransact.payments.domain;

import java.util.List;

/**
 * "Your balance" of the payouts screen.
 *
 * @param available           transferred after the hold, minus payouts, provider fees and label charges not yet
 *                            recovered — what can be requested now; negative when label charges exceed it
 * @param onHold              per order, with the date each amount becomes available
 * @param inFlight            requested, held by Finance, or on the way
 * @param paid                paid out so far
 * @param providerFees        provider fees debited from (or reserved in) the connected account, e.g. late
 *                            cancellation fees
 * @param labelCharges        shipping labels bought through AfroTransact and not yet recovered from a transfer
 *                            (S256); negative when voided labels are owed back and paid with the next transfer
 * @param planFees            plan fees withheld from earnings (S298) and not yet recovered from a transfer: they come
 *                            out of the amounts on hold; only the part those cannot cover reserves available.
 *                            Negative when a released fee is owed back and paid with the next transfer
 * @param deliveryFees        AfroTransact Delivery pickup fees the organisation has paid on captured lines (S259), net of
 *                            fees given back by customer-fault refunds: already deducted from its nets, shown so
 *                            earnings can list them
 * @param minimum             minimum payout
 * @param canRequestToday     false once today's request was made
 * @param payoutsPaused       a compliance hold from seller-service blocks requests and approvals (S214)
 * @param payoutsPausedReason AUTO_PAST_DUE, MANUAL, DEAUTHORIZED or CHARGES_DISABLED while paused, else null
 */
public record PayoutBalance(String orgId, String currency, Money available, List<HeldOrder> onHold,
                            Money onHoldTotal, Money inFlight, Money paid, Money providerFees, Money labelCharges,
                            Money planFees, Money deliveryFees, Money minimum,
                            boolean canRequestToday, boolean payoutsPaused, String payoutsPausedReason) {

    public PayoutBalance {
        onHold = List.copyOf(onHold);
    }
}
