package com.afrotransact.orders.application;

/**
 * Customer-service late pre-order adds (S272): orders created on a customer's behalf by delivery-runs-service
 * (scope {@code orders:on-behalf}), charged off-session on the saved card or paid on AfroTransact's pay page through
 * an SMS pay-now link.
 */
public interface LatePreorderService {

    /**
     * Creates the order (channel AGENT_LATE, tag LATE_ADD, staff actor) and charges or links its payment. Replays of
     * the same Idempotency-Key for the customer return the same order.
     *
     * @throws CheckoutFailed when the saved card was declined or the order could not be created
     */
    LatePreorderView create(LatePreorderCommand command);

    /**
     * A payment event of a late add: authorised → placed; failed or cancelled → voided (a pay-now link stays open
     * after a declined attempt until it expires).
     *
     * @return false when the order is not a late add
     */
    boolean onPayment(String orderId, boolean authorized, boolean cancelled, String failureMessage);

    /**
     * Voids late adds whose pay-now link expired unpaid, cancelling their PaymentIntent first (payments' key
     * {@code <orderId>:cancel}). Returns how many were voided.
     */
    int expireUnpaid();
}
