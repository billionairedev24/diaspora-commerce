package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload of {@code preorder.window.*} and {@code preorder.comms.due}: the window as notifications, content and the
 * storefront need it. Instants are ISO-8601 UTC; {@code deliversOn} is the local delivery day.
 *
 * @param windowId      window
 * @param code          public code, e.g. LUB-7
 * @param name          window name
 * @param status        derived status at the event
 * @param kind          comms.due only: ANNOUNCE, REMIND_48H, LAST_CALL_6H or CLOSED
 * @param dueAt         comms.due only: when the message was due
 * @param targets       area keys, e.g. US/TX/Lubbock
 * @param where         display, e.g. "Lubbock, TX"
 * @param opensAt       ordering opens
 * @param closesAt      ordering closes
 * @param deliversOn    delivery day
 * @param timeZone      market time zone
 * @param ribbon        ribbon text
 * @param headline      hero headline
 * @param smsCopy       announcement SMS
 * @param categories    categories in the window
 * @param door          door delivery offered
 * @param pickup        pickup offered
 * @param doorFeeMinor  effective door fee (null without door delivery)
 * @param pickupFeeMinor effective pickup fee (null without pickup)
 * @param currency      ISO 4217
 * @param pickupWindow  pickup hours, e.g. "10am–2pm"
 * @param pickupAddress pickup point address
 * @param comms         communications switched on
 * @param heroUrl       hero creative URL
 * @param emailImageUrl email header creative URL
 * @param runId         the run the orders were handed to (closed only)
 * @param pageUrl       storefront pre-order page
 * @param links         pageUrl with {@code src/po/cr} per channel (email, sms, push, whatsapp, instagram, facebook,
 *                      tiktok)
 * @param supportPhone  "Missed it? Call …"
 * @param opensDisplay  market-local, e.g. "Tue, Oct 6 9:00 AM"
 * @param closesDisplay market-local, e.g. "Thu, Oct 22 11:59 PM"
 * @param deliversDisplay e.g. "Sun, Oct 25"
 * @param orders        comms.due CLOSED only: the window's paid orders (notifications resolves contacts)
 * @param actorId       staff subject who made the change (paused / resumed / updated; null for scheduler events)
 * @param minOrders     the window's minimum order count (0 = none)
 * @param decision      decision_needed / decided / cancelled only: what was decided, by whom, why
 */
public record PreorderEventPayload(UUID windowId, String code, String name, String status, String kind,
                                   Instant dueAt, List<String> targets, String where, Instant opensAt,
                                   Instant closesAt, LocalDate deliversOn, String timeZone, String ribbon,
                                   String headline, String smsCopy, List<String> categories, boolean door,
                                   boolean pickup, Long doorFeeMinor, Long pickupFeeMinor, String currency,
                                   String pickupWindow, String pickupAddress, List<String> comms, String heroUrl,
                                   String emailImageUrl, UUID runId, String pageUrl, Map<String, String> links,
                                   String supportPhone, String opensDisplay, String closesDisplay,
                                   String deliversDisplay, List<OrderRef> orders, String actorId, int minOrders,
                                   Decision decision) {

    /**
     * A decision about the window.
     *
     * @param kind    AWAITING, PROCEED, CANCEL or EARLY_CLOSE
     * @param reason  why (the system's note for AWAITING)
     * @param actor   staff subject or {@code system}
     * @param orders  orders that counted
     * @param minimum the window's minimum
     * @param at      when
     */
    public record Decision(String kind, String reason, String actor, int orders, int minimum, Instant at) {
    }

    /**
     * A paid order of the window.
     *
     * @param orderId    order
     * @param customerId customer (token subject)
     * @param fulfilment DOOR or PICKUP
     */
    public record OrderRef(String orderId, String customerId, String fulfilment) {
    }
}
