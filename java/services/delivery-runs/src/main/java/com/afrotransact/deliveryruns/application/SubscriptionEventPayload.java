package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code delivery-runs.subscription.*}.
 *
 * @param subscriptionId    subscription
 * @param locationId        location
 * @param locationSlug      location page key
 * @param town              town / area name
 * @param userId            signed-in shopper (notifications resolves contacts), or null
 * @param email             email-only subscriber's address, or null
 * @param status            PENDING_CONFIRMATION, ACTIVE or CANCELLED
 * @param channels          channels with consent in force
 * @param changedChannels   channels granted (created) or withdrawn (cancelled) by this change
 * @param source            where consent was given
 * @param confirmUrl        double opt-in link (pending email subscribers only)
 * @param unsubscribeUrl    one-click unsubscribe link
 * @param locationUrl       storefront location page
 * @param whatsappInviteUrl the location's WhatsApp community invite link
 */
public record SubscriptionEventPayload(UUID subscriptionId, UUID locationId, String locationSlug, String town,
                                       String userId, String email, String status, List<String> channels,
                                       List<String> changedChannels, String source, String confirmUrl,
                                       String unsubscribeUrl, String locationUrl, String whatsappInviteUrl) {
}
