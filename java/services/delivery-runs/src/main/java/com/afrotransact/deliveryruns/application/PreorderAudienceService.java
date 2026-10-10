package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;

/**
 * Who hears about a pre-order window: the waitlist (public sign-ups) and the audience notifications pages through
 * for {@code preorder.comms.due} (scope {@code delivery-runs:audience.read}).
 */
public interface PreorderAudienceService {

    String SUBSCRIBERS = "SUBSCRIBERS";
    String WAITLIST = "WAITLIST";

    /**
     * Joins the waitlist of an area (no sign-in): {@code area} ({@code US/TX/Lubbock}) or country / region / city.
     * Idempotent per email and area; publishes {@code preorder.waitlist.joined}.
     */
    void joinWaitlist(String email, String area, String country, String region, String city, String source);

    /** One-click leave from the waitlist email (unknown tokens are 422). */
    void leaveWaitlist(String token);

    /**
     * A page of the window's audience on a channel. SUBSCRIBERS: subscribers of the delivery-runs locations in the
     * window's targets with consent on the channel, plus past pre-order buyers in those targets (each person once;
     * notifications enforces SMS consent). WAITLIST: active waitlist sign-ups of the window's areas (EMAIL only).
     *
     * @param after cursor from the previous page (null for the first)
     */
    AudiencePageView audience(UUID windowId, Channel channel, String segment, String after, int limit);

    /**
     * @param members people to message
     * @param next    cursor of the next page (null on the last)
     */
    record AudiencePageView(List<Member> members, String next) {
    }

    /**
     * @param subscriptionId subscription or waitlist id ({@code buyer:<customerId>} for past buyers)
     * @param userId         signed-in shopper (notifications resolves contacts), or null
     * @param email          address when known
     * @param phone          phone when known (past buyers)
     * @param unsubscribeUrl one-click unsubscribe link (null for past buyers: notifications' own preferences apply)
     */
    record Member(String subscriptionId, String userId, String email, String phone, String unsubscribeUrl) {
    }
}
