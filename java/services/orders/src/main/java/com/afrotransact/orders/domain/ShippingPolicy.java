package com.afrotransact.orders.domain;

/** Shipping fee and fallback delivery estimate per delivery method. */
public interface ShippingPolicy {

    /** Whether sellers may ship themselves and charge their own shipping (staff switch, off by default). */
    boolean selfShippingEnabled();

    /** Shipping charged for one shipment whose items cost {@code itemsMinor}. */
    long fee(DeliveryMethod method, String serviceLevel, long itemsMinor);

    /** Business days to delivery used when the promise service does not answer. */
    int estimatedDays(DeliveryMethod method, String serviceLevel);

    /** The service level stored for a method when the request did not name one. */
    String defaultServiceLevel(DeliveryMethod method);
}
