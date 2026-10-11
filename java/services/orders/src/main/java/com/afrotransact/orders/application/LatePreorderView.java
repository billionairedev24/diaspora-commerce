package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

/**
 * Answer of {@code POST /v1/service/orders/late-preorder}.
 *
 * @param status               PLACED (saved card charged) or AWAITING_PAYMENT (pay-now link sent, or the bank asks
 *                             the customer to authenticate the saved card)
 * @param payment              SAVED_CARD | PAYMENT_LINK
 * @param paymentLinkUrl       PAYMENT_LINK: the pay-now link texted to the customer, AfroTransact's pay page with its
 *                             signed token ({@code <storefront>/orders/<number>/pay?token=…}); never a Stripe page
 * @param paymentLinkExpiresAt when the link expires; the order is voided (intent cancelled) if unpaid by then
 * @param channel              AGENT_LATE
 * @param tags                 LATE_ADD
 */
public record LatePreorderView(String orderId, String orderNumber, String status, String payment,
                               String paymentLinkUrl, Instant paymentLinkExpiresAt, long totalMinor,
                               long deliveryFeeMinor, String currency, String windowId, String fulfilment,
                               String channel, List<String> tags, List<String> shipmentIds, boolean replayed) {
}
