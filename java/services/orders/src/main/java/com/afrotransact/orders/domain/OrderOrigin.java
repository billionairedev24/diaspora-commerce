package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * How an order came about (S272): its attribution and channel, tags (LATE_ADD), and for orders created on behalf of a
 * customer the staff actor, reason, note and the pay-now link the customer pays with.
 *
 * @param attribution          storefront attribution (null when none)
 * @param channel              SITE_DIRECT by default; AGENT_LATE for late pre-order adds
 * @param tags                 e.g. LATE_ADD
 * @param actorId              staff subject who created the order on the customer's behalf (null: the customer)
 * @param reason               why it was created on behalf (Customer called, Chat, Driver report, Other)
 * @param note                 free text of the staff member
 * @param paymentLinkUrl       PAYMENT_LINK late adds: AfroTransact's pay page of the order
 *                             ({@code <storefront>/orders/<number>/pay}, without the signed token; orders created
 *                             before hosted links were removed may hold a Stripe URL, never used again)
 * @param paymentLinkExpiresAt when the link expires (the order is voided and its PaymentIntent cancelled if unpaid)
 */
public record OrderOrigin(Attribution attribution, OrderChannel channel, List<String> tags, String actorId,
                          String reason, String note, String paymentLinkUrl, Instant paymentLinkExpiresAt) {

    public static final String TAG_LATE_ADD = "LATE_ADD";

    public OrderOrigin {
        Objects.requireNonNull(channel, "channel");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A shopper's own checkout without attribution. */
    public static OrderOrigin none() {
        return new OrderOrigin(null, OrderChannel.SITE_DIRECT, List.of(), null, null, null, null, null);
    }

    /** A shopper's own checkout with the storefront's attribution (channel from its source). */
    public static OrderOrigin checkout(Attribution attribution) {
        return attribution == null ? none() : new OrderOrigin(attribution,
                OrderChannel.fromSource(attribution.src()), List.of(), null, null, null, null, null);
    }

    /** A late pre-order add by customer service. */
    public static OrderOrigin lateAdd(String actorId, String reason, String note, String windowCode) {
        return new OrderOrigin(new Attribution("agent_late", null, windowCode), OrderChannel.AGENT_LATE,
                List.of(TAG_LATE_ADD), actorId, reason, note, null, null);
    }

    public boolean lateAdd() {
        return tags.contains(TAG_LATE_ADD);
    }

    /** The origin with the pay page the customer was sent a link to. */
    public OrderOrigin withPaymentLink(String url, Instant expiresAt) {
        return new OrderOrigin(attribution, channel, tags, actorId, reason, note, url, expiresAt);
    }
}
