package com.afrotransact.deliveryruns.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.LatePaymentMethod;
import com.afrotransact.deliveryruns.domain.PreorderLine;

/**
 * orders-service (local port 8104): {@code POST /v1/service/orders/late-preorder} with the client-credentials token
 * of {@code delivery-runs-service} (scope {@code orders:on-behalf}). Throws {@link UpstreamUnavailableException}
 * when orders cannot answer and {@link UpstreamRejectedException} when it refuses the order.
 */
public interface OrdersGateway {

    LatePreorderResult placeLatePreorder(LatePreorderRequest request);

    /**
     * Body of {@code POST /v1/service/orders/late-preorder} (orders' {@code LatePreorderRequest}): an on-behalf
     * pre-order for a customer, channel AGENT_LATE.
     *
     * @param idempotencyKey sent as {@code Idempotency-Key}; retries create one order
     * @param windowId       pre-order window
     * @param customerId     identity subject of the customer
     * @param address        delivery address snapshot (saved or new, verified)
     * @param phoneConfirmed the agent confirmed the phone number with the customer
     * @param items          products and quantities
     * @param fulfilment     {@code DOOR} or {@code PICKUP}
     * @param payment        saved card (off-session) or payment link by SMS
     * @param reason         why the order is late (label, e.g. "Customer called")
     * @param note           agent note
     * @param actorId        the staff member placing the order (audit)
     * @param lateAdd        the payment setup's late-add options
     */
    record LatePreorderRequest(String idempotencyKey, UUID windowId, String customerId, Address address,
                               boolean phoneConfirmed, List<Item> items, String fulfilment, LatePaymentMethod payment,
                               String reason, String note, String actorId, LateAddTerms lateAdd) {

        public LatePreorderRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Delivery address snapshot (orders' {@code LatePreorderAddress}).
     *
     * @param addressId    saved address id (null for a new address)
     * @param fullName     recipient
     * @param phone        recipient phone
     * @param line1        street line
     * @param line2        second line
     * @param city         city
     * @param state        state / region code
     * @param postalCode   postal code
     * @param country      ISO-3166 alpha-2
     * @param instructions delivery note
     */
    record Address(String addressId, String fullName, String phone, String line1, String line2, String city,
                   String state, String postalCode, String country, String instructions) {
    }

    /**
     * Late-add payment options sent with the order.
     *
     * @param savedCard       saved card allowed
     * @param paymentLink     payment link allowed
     * @param linkExpiryHours payment link lifetime (orders accepts 1–24)
     */
    record LateAddTerms(boolean savedCard, boolean paymentLink, int linkExpiryHours) {
    }

    /**
     * A new address entered by the agent.
     *
     * @param line1   street line
     * @param zip     postal code
     * @param placeId Google Places id it was verified with
     */
    record NewAddress(String line1, String zip, String placeId) {
    }

    /** A product and its quantity. */
    record Item(String atin, int qty) {
    }

    /**
     * What orders-service created ({@code LatePreorderView}; contact and lines when it sends them).
     *
     * @param orderId        order id
     * @param orderNumber    orders-service number
     * @param status         e.g. PLACED or AWAITING_PAYMENT
     * @param totalMinor     pre-order total incl. delivery
     * @param currency       ISO 4217
     * @param paymentLinkUrl payment link (PAYMENT_LINK only)
     * @param paymentLinkExpiresAt when the payment link expires
     * @param shipmentId     the PREORDER package created
     * @param customerName   recipient
     * @param phone          recipient phone
     * @param email          customer email
     * @param line1          street line
     * @param city           city
     * @param region         state / region
     * @param zip            postal code
     * @param lines          products (name and seller when orders returns them)
     */
    record LatePreorderResult(String orderId, String orderNumber, String status, long totalMinor, String currency,
                              String paymentLinkUrl, Instant paymentLinkExpiresAt, String shipmentId, String customerName, String phone,
                              String email, String line1, String city, String region, String zip,
                              List<PreorderLine> lines) {

        public LatePreorderResult {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
