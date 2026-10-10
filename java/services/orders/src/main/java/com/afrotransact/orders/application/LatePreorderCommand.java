package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.ShipTo;

/**
 * {@code POST /v1/service/orders/late-preorder} (S272): delivery-runs creates an order on a customer's behalf for a
 * pre-order window after customer service took it by phone, chat or a driver report.
 *
 * @param idempotencyKey  required; the same key returns the same order
 * @param customerId      the customer (identity subject) the order belongs to
 * @param windowId        the pre-order window (OPEN, or CLOSED and not delivered)
 * @param shipTo          delivery address snapshot (saved or new, verified by delivery-runs)
 * @param items           variant ATINs and quantities (priced from the catalog)
 * @param fulfilment      DOOR | PICKUP; null = door when the window offers it
 * @param payment         SAVED_CARD (off-session on the default saved card) or PAYMENT_LINK (sent by SMS)
 * @param reason          Customer called, Chat, Driver report, Other
 * @param note            staff note
 * @param actorId         staff subject who took the order
 * @param phoneConfirmed  staff confirmed the customer's phone number (must not be false)
 * @param lateAdd         back-office late-add settings of the window (which payments are allowed, link life)
 */
public record LatePreorderCommand(String idempotencyKey, String customerId, String windowId, ShipTo shipTo,
                                  List<Item> items, String fulfilment, Payment payment, String reason, String note,
                                  String actorId, Boolean phoneConfirmed, LateAddSettings lateAdd) {

    public LatePreorderCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** How the customer pays. */
    public enum Payment {
        SAVED_CARD, PAYMENT_LINK
    }

    /** One requested item. */
    public record Item(String variantAtin, int quantity) {
    }

    /**
     * {@code settings.lateAdd} of the pre-order payment setup (back office).
     *
     * @param savedCard       charging the saved card is allowed (null = yes)
     * @param paymentLink     payment links are allowed (null = yes)
     * @param linkExpiryHours life of a payment link (null = 24, at most 24)
     */
    public record LateAddSettings(Boolean savedCard, Boolean paymentLink, Integer linkExpiryHours) {
    }
}
