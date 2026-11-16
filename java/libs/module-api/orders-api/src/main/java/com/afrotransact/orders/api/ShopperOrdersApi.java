package com.afrotransact.orders.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.module.EndUserActor;

/**
 * The signed-in shopper's own orders, read in-process for a call made on their behalf (the AI shopping agent's "my orders" and
 * "track order" tools): the in-process equivalent of {@code GET /v1/orders?customer=me} and {@code GET /v1/orders/{number}} with the
 * shopper's token and {@code X-Acting-As: CUSTOMER}.
 *
 * <p>Every method takes the person explicitly ({@link EndUserActor}, built from the request's authenticated security context, never
 * from a parameter) and the provider runs orders' own query service with that person as the actor in force, so a shopper only ever
 * sees their own orders (another customer's order is 404/403 exactly as over HTTP). The calling module needs the delegation scope
 * {@value #CALLER_SCOPE}; without it the call is refused with {@code ModuleApiException} 403 {@code auth.missing-permission}.
 */
public interface ShopperOrdersApi {

    /** The scope a module needs to read orders on behalf of its signed-in users. */
    String CALLER_SCOPE = "orders:mine.read";

    /** The person's most recent orders, newest first (at most {@code limit}). */
    List<OrderSummary> mine(EndUserActor user, int limit);

    /** One of the person's orders by number or id; 404 / 403 when it is not theirs, as over HTTP. Never carries handover codes. */
    TrackedOrder track(EndUserActor user, String numberOrId);

    /** One order in the list ({@code placedAt} falls back to the creation time). */
    record OrderSummary(String orderNumber, String status, Instant placedAt, long totalMinor, String currency, int shipments) {
    }

    /** An order with its shipments' tracking. */
    record TrackedOrder(String orderNumber, String status, List<TrackedShipment> shipments) {

        public TrackedOrder {
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }
    }

    /** One shipment's status and tracking. */
    record TrackedShipment(String shipmentId, String status, String carrier, String trackingNumber, LocalDate promisedBy) {
    }
}
