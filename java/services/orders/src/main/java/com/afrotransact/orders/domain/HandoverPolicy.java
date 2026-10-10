package com.afrotransact.orders.domain;

/** When a 4-digit handover code is needed, and how it is generated and checked. */
public interface HandoverPolicy {

    /** Code needed for AfroTransact Delivery / pickup of perishables or when the customer asked for hand delivery. */
    boolean required(boolean perishable, DeliveryMethod method, DeliveryPreferences preferences);

    /** A fresh random 4-digit code. */
    String newCode();

    /** One-way hash bound to the shipment, stored instead of the code. */
    String hash(String shipmentId, String code);

    /** Constant-time comparison of a code typed by the driver with the stored hash. */
    boolean matches(String shipmentId, String code, String storedHash);
}
