package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PickupPoint;

/**
 * Payload of every {@code delivery-runs.run.*} event: what notifications, content, orders, payments and fulfilment
 * need without calling back. Instants are ISO-8601 UTC; the {@code …Display} fields are pre-formatted in the location's
 * time zone for message templates.
 *
 * @param runId             run
 * @param status            status after the change
 * @param previousStatus    status before
 * @param locationId        location
 * @param locationSlug      location page key
 * @param town              town / area name
 * @param market            market code
 * @param state             state
 * @param timeZone          IANA zone of the location
 * @param windowStart       delivery window start
 * @param windowEnd         delivery window end
 * @param cutoffAt          order cutoff (the new one for extensions)
 * @param previousCutoffAt  cutoff before an extension
 * @param sellerPickupAt    seller sweep time, when scheduled
 * @param deliveryDay       e.g. "Saturday, October 24"
 * @param windowDisplay     e.g. "10:00 AM – 2:00 PM"
 * @param cutoffDisplay     e.g. "Thu, Oct 22 · 8:00 PM CDT"
 * @param feeMinor          run delivery fee, minor units
 * @param currency          currency
 * @param feeDisplay        e.g. "$7.99"
 * @param dropMode          DOORSTEP or PICKUP_POINT
 * @param pickupPoint       pickup point of a PICKUP_POINT run
 * @param minOrders         minimum orders
 * @param reservedOrders    orders holding a slot
 * @param capacityOrders    order capacity (null = unlimited)
 * @param extensions        extensions so far
 * @param reason            why (extended / cancelled)
 * @param runUrl            storefront run link (no UTM)
 * @param links             the run link attributed per channel: email, sms, push, whatsapp, social
 * @param locationUrl       storefront location page
 * @param whatsappInviteUrl the location's WhatsApp community invite link
 * @param orders            affected orders (confirmed, cancelled, out_for_delivery, completed); null otherwise
 */
public record RunEventPayload(UUID runId, String status, String previousStatus, UUID locationId, String locationSlug,
                              String town, String market, String state, String timeZone, Instant windowStart,
                              Instant windowEnd, Instant cutoffAt, Instant previousCutoffAt, Instant sellerPickupAt,
                              String deliveryDay, String windowDisplay, String cutoffDisplay, long feeMinor,
                              String currency, String feeDisplay, String dropMode, PickupPoint pickupPoint,
                              int minOrders, int reservedOrders, Integer capacityOrders, int extensions, String reason,
                              String runUrl, Map<String, String> links, String locationUrl, String whatsappInviteUrl,
                              List<OrderRef> orders) {

    /**
     * An order on the run.
     *
     * @param orderId      orders-service order id
     * @param customerId   customer (token subject)
     * @param packages     packages
     * @param status       slot status
     * @param handoverCode pickup-point handover code (CONFIRMED pickup-point runs)
     */
    public record OrderRef(String orderId, String customerId, int packages, String status, String handoverCode) {
    }
}
