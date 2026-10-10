package com.afrotransact.orders.application;

/** Checkout: starts the saga and reports its outcome. */
public interface CheckoutService {

    /**
     * Takes the cart and address snapshot with the customer's token and starts the checkout saga, then waits briefly
     * for it to reach "awaiting payment". Replays of the same Idempotency-Key return the same order.
     *
     * @throws CheckoutFailed when the saga ended without an order (insufficient stock, disabled market …)
     */
    CheckoutView checkout(CheckoutCommand command);

    /** The client confirmed the payment: ask payments for the intent status and signal the saga. */
    CheckoutView confirmPayment(String orderNumber);

    /** Current state of a checkout of the calling customer. */
    CheckoutView status(String orderNumber);
}
