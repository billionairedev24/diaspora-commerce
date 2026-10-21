package com.afrotransact.ai.port;

import java.util.List;

/**
 * The shopper's own account data, read under their relayed token (they only ever see their own): recent orders, the
 * cart and service bookings. Each answer says whether the service responded, so Lola can say "not available yet"
 * instead of guessing.
 */
public interface AccountReader {

    Result<List<OrderSummary>> recentOrders(String bearerToken, int limit);

    Result<CartSummary> cart(String bearerToken);

    Result<List<BookingSummary>> bookings(String bearerToken);

    /** The shopper's returns (RMAs) and their refunds, newest first; {@code orderNumber} narrows to one order. */
    Result<List<ReturnSummary>> returns(String bearerToken, String orderNumber);

    /**
     * Outcome of a read.
     *
     * @param available false when the service is not deployed yet, timed out or refused
     * @param value     the data (empty or null when not available)
     */
    record Result<T>(boolean available, T value) {

        public static <T> Result<T> unavailable() {
            return new Result<>(false, null);
        }

        public static <T> Result<T> of(T value) {
            return new Result<>(true, value);
        }
    }

    /** One order in the list. */
    record OrderSummary(String orderNumber, String status, String placedAt, long totalMinor, String currency,
                        int shipments) {
    }

    /** One cart line. */
    record CartLine(String atin, String title, int quantity, long priceMinor, boolean unavailable,
                    boolean priceChanged) {
    }

    /** The cart: lines grouped by seller are flattened; subtotal is for the available lines. */
    record CartSummary(int itemCount, long subtotalMinor, String currency, boolean checkoutable,
                       List<CartLine> lines) {

        public CartSummary {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * One return and the refund that follows it.
     *
     * @param number        return number (RMA-XXXXXXXX)
     * @param orderNumber   the order it belongs to
     * @param status        return status (requested, label issued, received, refunded ...)
     * @param method        drop-off, driver pickup or no return needed
     * @param refundState   state of the refund (pending, issued, failed ...), blank before one exists
     * @param refundMinor   the refund amount or estimate
     * @param currency      ISO currency of the refund
     * @param dropOffBy     deadline to hand the parcel over, if any
     * @param createdAt     when the return was started
     * @param lines         the items being returned
     */
    record ReturnSummary(String number, String orderNumber, String status, String method, String refundState,
                         long refundMinor, String currency, String dropOffBy, String createdAt,
                         List<ReturnLine> lines) {

        public ReturnSummary {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One item in a return. */
    record ReturnLine(String title, int quantity, String reason, String outcome) {
    }

    /** One service booking. */
    record BookingSummary(String reference, String service, String status, String startAt, long priceMinor,
                          long depositMinor) {
    }
}
