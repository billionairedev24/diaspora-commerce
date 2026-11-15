package com.afrotransact.inventory.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What other modules may ask inventory in-process about checkout reservations: the calls orders and fulfilment make over HTTP
 * today ({@code /v1/reservations...}, scope {@code inventory:reserve}; the read needs {@code inventory:read}). Every call runs
 * under an in-process caller principal ({@code com.afrotransact.module.InProcessCalls}); inventory's own permission checks
 * apply and a caller without the scope is refused with {@code ModuleApiException} status 403.
 *
 * <p>Failures arrive as {@code com.afrotransact.module.ModuleApiException} with the status and code the endpoint answers
 * ({@code inventory.insufficient-stock} 409 with one detail line per short SKU, {@code inventory.group-not-found} 404, ...).
 */
public interface ReservationApi {

    /** All lines or none; the order id is the idempotency key, a replay returns the stored reservation. */
    ReservationInfo reserve(String orderId, String idempotencyKey, List<Line> lines);

    /** @return the order's reservation, empty when there is none (HTTP 404) */
    Optional<ReservationInfo> find(String orderId);

    /** Payment authorised: the hold no longer expires. */
    ReservationInfo confirm(String orderId);

    /** Ship the whole reservation. */
    void commit(String orderId);

    /** Give the stock back; idempotent. */
    ReservationInfo release(String orderId, String reason);

    /** Ship only the held lines of these groups; 404 {@code inventory.group-not-found} when the reservation has none. */
    void commitGroups(String orderId, Set<String> groups);

    /** Give back only the held lines of these groups; 404 as for {@link #commitGroups}. */
    ReservationInfo releaseGroups(String orderId, Set<String> groups, String reason);

    /**
     * @param nodeId fulfilment node chosen by the promise service; null lets inventory pick
     * @param group  caller's grouping key (orders: the order line id)
     */
    record Line(String sku, String nodeId, int qty, String group) {
    }

    /** @param status HELD, CONFIRMED, COMMITTED, RELEASED or EXPIRED */
    record ReservationInfo(String orderId, String status, boolean replayed, List<ReservedLine> lines) {

        public ReservationInfo {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One reserved line with the node and the lots it was allocated from. */
    record ReservedLine(String sku, String nodeId, int qty, String group, List<String> lotIds) {

        public ReservedLine {
            lotIds = lotIds == null ? List.of() : List.copyOf(lotIds);
        }
    }
}
