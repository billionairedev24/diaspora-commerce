package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

/**
 * One page of a location's opted-in subscribers on a channel, for notifications' fan-out.
 *
 * @param members subscribers
 * @param next    cursor of the next page (null on the last page)
 */
public record AudiencePage(List<Member> members, UUID next) {

    /**
     * A subscriber.
     *
     * @param subscriptionId subscription (also the cursor)
     * @param userId         signed-in shopper (notifications resolves the contact), or null
     * @param email          email-only subscriber, or null
     * @param unsubscribeUrl one-click unsubscribe link for the message footer
     */
    public record Member(UUID subscriptionId, String userId, String email, String unsubscribeUrl) {
    }
}
