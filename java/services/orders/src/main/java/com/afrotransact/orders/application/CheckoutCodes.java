package com.afrotransact.orders.application;

/** Stable failure codes of the checkout saga (returned in problem details and stored on the checkout request). */
public final class CheckoutCodes {

    public static final String CART_EMPTY = "orders.cart-empty";
    public static final String CART_NOT_CHECKOUTABLE = "orders.cart-not-checkoutable";
    public static final String CURRENCY_MISMATCH = "orders.currency-mismatch";
    public static final String MARKET_DISABLED = "orders.market-disabled";
    public static final String ADDRESS_NOT_FOUND = "orders.address-not-found";
    public static final String INVALID_SELECTION = "orders.invalid-delivery-selection";
    public static final String INSUFFICIENT_STOCK = "orders.insufficient-stock";
    public static final String PAYMENT_FAILED = "orders.payment-failed";
    public static final String PAYMENT_TIMEOUT = "orders.payment-timeout";
    public static final String RESERVATION_EXPIRED = "orders.reservation-expired";
    public static final String UPSTREAM_REJECTED = "orders.upstream-rejected";
    public static final String COUPON_NOT_APPLICABLE = "orders.coupon-not-applicable";
    public static final String UPSTREAM_UNAVAILABLE = "orders.upstream-unavailable";
    /** S255: the customer is beyond the delivery radius of the seller's pickup location (offer not deliverable). */
    public static final String DELIVERY_OUT_OF_RANGE = "orders.delivery-out-of-range";
    /** S255: the fee schedule in force offers no AfroTransact Delivery from seller stores. */
    public static final String DELIVERY_UNAVAILABLE = "orders.delivery-unavailable";
    /** S255: the offer's pickup location is unknown, not geocoded or no longer enabled for AfroTransact pickup. */
    public static final String DELIVERY_PICKUP_UNAVAILABLE = "orders.delivery-pickup-unavailable";
    /** S272: a cart line is a pre-order whose window is upcoming or closed (not open): it cannot be bought now. */
    public static final String PREORDER_NOT_OPEN = "orders.preorder-not-open";
    /** S272: a late pre-order add names a product that is not sold in that window at the customer's address. */
    public static final String PREORDER_NOT_ELIGIBLE = "orders.preorder-not-eligible";
    /** S261: the delivery run the order was priced for closed (its cutoff passed) before the order could take a slot. */
    public static final String RUN_CLOSED = "orders.run-closed";
    /** S261: the delivery run the order was priced for is full. */
    public static final String RUN_FULL = "orders.run-full";
    /** S255: geo could not locate the delivery address to measure the distance. */
    public static final String DELIVERY_ADDRESS_NOT_LOCATED = "orders.delivery-address-not-located";
    /** The customer's previous checkout is still being settled (payment processing or compensations running): 409,
     *  retry with the same Idempotency-Key. */
    public static final String CHECKOUT_IN_PROGRESS = "orders.checkout-in-progress";
    /** The customer's previous open checkout turned out to be paid: it is placed, the new attempt is refused (409). */
    public static final String PREVIOUS_CHECKOUT_PAID = "orders.previous-checkout-paid";
    /** Failure code of a checkout replaced by a newer one with a different cart (its intent cancelled). */
    public static final String CHECKOUT_SUPERSEDED = "orders.checkout-superseded";
    /** A second identical late pre-order add (same customer, window, items) within the duplicate window (409). */
    public static final String LATE_ADD_DUPLICATE = "orders.late-add-duplicate";

    private CheckoutCodes() {
    }
}
