package com.afrotransact.payments.domain;

/**
 * Who caused a refund, for AfroTransact Delivery lines (S255). The trip was made (lines are captured at driver
 * pickup), so it is paid once, by whoever is at fault:
 * <ul>
 *   <li>{@link #SELLER} (default: wrong, damaged or missing item, seller cancellation): the customer may get the
 *       delivery charge back (Dr 4200) and AfroTransact keeps the seller's pickup-delivery fee (4250).</li>
 *   <li>{@link #CUSTOMER} (changed their mind, refused the delivery): the customer's delivery charge is not refunded
 *       (the trip was made for them), and the seller gets its pickup-delivery fee back in proportion to the items
 *       refunded (Dr 4250), like commission.</li>
 * </ul>
 * Other lines are unaffected.
 */
public enum RefundFault {
    SELLER,
    CUSTOMER
}
