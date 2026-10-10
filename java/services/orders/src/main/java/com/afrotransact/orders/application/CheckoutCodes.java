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

    private CheckoutCodes() {
    }
}
