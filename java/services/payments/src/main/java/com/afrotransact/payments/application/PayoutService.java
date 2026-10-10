package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.PayoutBalance;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.PayoutStatus;

/**
 * Payouts on request (connected accounts use the manual payout schedule): balance, request (min USD 25, one per
 * day, owner / finance only), Finance queue with approve / hold / release; payout.paid / payout.failed webhooks
 * close the request.
 */
public interface PayoutService {

    PayoutBalance balance(String orgId, String currency);

    PayoutRequest request(PayoutCommand command, String idempotencyKey);

    List<PayoutRequest> queue(PayoutStatus status, String orgId, int limit);

    PayoutRequest get(UUID id);

    /** Creates the Stripe payout on the organisation's connected account. */
    PayoutRequest approve(UUID id);

    PayoutRequest hold(UUID id, String reason);

    PayoutRequest release(UUID id);
}
