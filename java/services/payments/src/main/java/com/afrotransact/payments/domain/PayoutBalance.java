package com.afrotransact.payments.domain;

import java.util.List;

/**
 * "Your balance" of the payouts screen.
 *
 * @param available     transferred after the hold and not yet requested — what can be requested now
 * @param onHold        per order, with the date each amount becomes available
 * @param inFlight      requested, held by Finance, or on the way
 * @param paid          paid out so far
 * @param minimum       minimum payout
 * @param canRequestToday false once today's request was made
 */
public record PayoutBalance(String orgId, String currency, Money available, List<HeldOrder> onHold,
                            Money onHoldTotal, Money inFlight, Money paid, Money minimum, boolean canRequestToday) {

    public PayoutBalance {
        onHold = List.copyOf(onHold);
    }
}
