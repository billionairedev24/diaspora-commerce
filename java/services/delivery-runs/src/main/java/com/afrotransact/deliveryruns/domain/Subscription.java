package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A shopper's run alerts for one location, with per-channel consent. Signed-in shoppers ({@code userId}) subscribe
 * on any channel and are active at once (notifications resolves their verified contacts); anonymous subscribers give
 * an email address, get email only, and must confirm it (double opt-in) before anything is sent.
 *
 * @param id                subscription id
 * @param locationId        location
 * @param userId            shopper (token subject), or null for an email-only subscriber
 * @param email             email-only subscriber's address (lower case), or null
 * @param status            pending confirmation, active or cancelled
 * @param consents          consent per channel (granted or withdrawn)
 * @param confirmTokenHash  SHA-256 of the pending confirmation token
 * @param confirmExpiresAt  when the confirmation link expires
 * @param unsubscribeToken  opaque token of the one-click unsubscribe link
 * @param createdAt         created
 * @param confirmedAt       double opt-in confirmed (or creation for signed-in shoppers)
 * @param updatedAt         last change
 * @param version           optimistic lock
 */
public record Subscription(UUID id, UUID locationId, String userId, String email, SubscriptionStatus status,
                           Map<Channel, ChannelConsent> consents, String confirmTokenHash, Instant confirmExpiresAt,
                           String unsubscribeToken, Instant createdAt, Instant confirmedAt, Instant updatedAt,
                           long version) {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Subscription {
        if ((userId == null) == (email == null)) {
            throw new RuleViolation("delivery-runs.invalid-subscriber", "A subscriber is a user id or an email");
        }
        consents = consents == null || consents.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(consents));
    }

    /** Signed-in shopper: active straight away on the chosen channels. */
    public static Subscription forUser(UUID id, UUID locationId, String userId, Set<Channel> channels, String source,
                                       String unsubscribeToken, Instant now) {
        if (userId == null || userId.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-subscriber", "User id is required");
        }
        Subscription s = new Subscription(id, locationId, userId, null, SubscriptionStatus.ACTIVE, Map.of(), null,
                null, unsubscribeToken, now, now, now, 0);
        return s.grant(channels, source, now);
    }

    /** Anonymous subscriber: email only, pending until the confirmation link is used. */
    public static Subscription forEmail(UUID id, UUID locationId, String email, String source, String confirmTokenHash,
                                        Instant confirmExpiresAt, String unsubscribeToken, Instant now) {
        String normalised = normaliseEmail(email);
        Map<Channel, ChannelConsent> consent = Map.of(Channel.EMAIL,
                new ChannelConsent(Channel.EMAIL, now, cleanSource(source), null));
        return new Subscription(id, locationId, null, normalised, SubscriptionStatus.PENDING_CONFIRMATION, consent,
                confirmTokenHash, confirmExpiresAt, unsubscribeToken, now, null, now, 0);
    }

    public static String normaliseEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches() || e.length() > 254) {
            throw new RuleViolation("delivery-runs.invalid-email", "Enter a valid email address");
        }
        return e;
    }

    /** Gives (or renews) consent on {@code channels}; a cancelled subscription becomes active again. */
    public Subscription grant(Set<Channel> channels, String source, Instant now) {
        if (channels == null || channels.isEmpty()) {
            throw new RuleViolation("delivery-runs.channels-required", "Choose at least one channel");
        }
        if (userId == null && !EnumSet.of(Channel.EMAIL).containsAll(channels)) {
            throw new RuleViolation("delivery-runs.sign-in-required",
                    "Sign in to get run alerts by SMS, push or WhatsApp");
        }
        Map<Channel, ChannelConsent> next = new EnumMap<>(Channel.class);
        next.putAll(consents);
        for (Channel c : channels) {
            ChannelConsent current = next.get(c);
            if (current == null || !current.granted()) {
                next.put(c, new ChannelConsent(c, now, cleanSource(source), null));
            }
        }
        SubscriptionStatus st = status == SubscriptionStatus.PENDING_CONFIRMATION ? status : SubscriptionStatus.ACTIVE;
        return new Subscription(id, locationId, userId, email, st, next, confirmTokenHash, confirmExpiresAt,
                unsubscribeToken, createdAt, confirmedAt, now, version);
    }

    /** Withdraws consent on {@code channels} (all channels when empty); no channel left → CANCELLED. */
    public Subscription withdraw(Set<Channel> channels, Instant now) {
        Set<Channel> which = channels == null || channels.isEmpty() ? EnumSet.allOf(Channel.class) : channels;
        Map<Channel, ChannelConsent> next = new EnumMap<>(Channel.class);
        consents.forEach((c, consent) -> next.put(c, which.contains(c) ? consent.withdraw(now) : consent));
        boolean any = next.values().stream().anyMatch(ChannelConsent::granted);
        SubscriptionStatus st = any ? status : SubscriptionStatus.CANCELLED;
        return new Subscription(id, locationId, userId, email, st, next, any ? confirmTokenHash : null,
                any ? confirmExpiresAt : null, unsubscribeToken, createdAt, confirmedAt, now, version);
    }

    /**
     * Double opt-in: the confirmation link was used before it expired. The token hash is kept, so a second click on
     * the same link finds the (already active) subscription instead of an error.
     */
    public Subscription confirm(Instant now) {
        if (status == SubscriptionStatus.ACTIVE) {
            return this;
        }
        if (status != SubscriptionStatus.PENDING_CONFIRMATION) {
            throw new RuleViolation("delivery-runs.subscription-cancelled", "This subscription was cancelled");
        }
        if (confirmExpiresAt != null && now.isAfter(confirmExpiresAt)) {
            throw new RuleViolation("delivery-runs.confirmation-expired", "The confirmation link has expired");
        }
        return new Subscription(id, locationId, userId, email, SubscriptionStatus.ACTIVE, consents, confirmTokenHash,
                confirmExpiresAt, unsubscribeToken, createdAt, now, now, version);
    }

    /** A new confirmation link for a pending (or cancelled) email subscriber who signs up again. */
    public Subscription requestConfirmation(String tokenHash, Instant expiresAt, String source, Instant now) {
        Map<Channel, ChannelConsent> consent = Map.of(Channel.EMAIL,
                new ChannelConsent(Channel.EMAIL, now, cleanSource(source), null));
        return new Subscription(id, locationId, userId, email, SubscriptionStatus.PENDING_CONFIRMATION, consent,
                tokenHash, expiresAt, unsubscribeToken, createdAt, null, now, version);
    }

    /** Channels that may be messaged now (none until the subscription is active). */
    public Set<Channel> activeChannels() {
        Set<Channel> out = EnumSet.noneOf(Channel.class);
        if (status == SubscriptionStatus.ACTIVE) {
            consents.values().stream().filter(ChannelConsent::granted).forEach(c -> out.add(c.channel()));
        }
        return out;
    }

    private static String cleanSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim().toLowerCase(Locale.ROOT);
    }
}
