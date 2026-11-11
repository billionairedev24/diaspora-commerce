package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

/**
 * Delivery quote events, published through the outbox on {@code at.order.events} with the quote id as the key
 * (contracts/asyncapi/fragments/orders.yaml). Payloads carry ids, references, amounts and city only: never an email,
 * phone number or street address (notifications resolves the shopper's contact from {@code customerId}).
 */
public final class QuoteEvents {

    public static final String REQUESTED = "orders.delivery-quote.requested";
    public static final String READY = "orders.delivery-quote.ready";
    public static final String DECLINED = "orders.delivery-quote.declined";
    public static final String EXPIRED = "orders.delivery-quote.expired";
    /** Staff: a request was assigned to you (or taken over, or auto-assigned). */
    public static final String ASSIGNED = "orders.delivery-quote.assigned";
    /** Staff: a request was escalated to you. */
    public static final String ESCALATED = "orders.delivery-quote.escalated";

    private QuoteEvents() {
    }

    /** Requested, declined or expired. */
    public record Lifecycle(String quoteId, String reference, String customerId, String place, int itemCount,
                            Instant at) {
    }

    /** The quote is ready: what the shopper may pay and until when. */
    public record Ready(String quoteId, String reference, String customerId, long amountMinor, String currency,
                        Instant expiresAt, Instant at) {
    }

    /** Assigned or escalated: whom it is for, with names. */
    public record Assignment(String quoteId, String reference, String place, String reason, String assignedTo,
                             String byName, List<String> recipientUserIds, int escalationLevel, String note,
                             Instant at) {
    }
}
