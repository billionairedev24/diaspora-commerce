package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A pre-order waitlist sign-up: "email me when a pre-order opens here" (the storefront's off state when the fallback
 * is WAITLIST). Email only; the opening notice carries a one-click unsubscribe link.
 *
 * @param id               entry id
 * @param area             the place asked for (city or whole region)
 * @param email            lower-case address
 * @param source           where it was given, e.g. preorder-waitlist
 * @param active           false once unsubscribed
 * @param unsubscribeToken opaque token of the unsubscribe link
 * @param createdAt        joined
 * @param updatedAt        last change
 * @param version          optimistic lock
 */
public record WaitlistEntry(UUID id, AreaKey area, String email, String source, boolean active,
                            String unsubscribeToken, Instant createdAt, Instant updatedAt, long version) {

    public WaitlistEntry {
        if (area == null) {
            throw new RuleViolation("delivery-runs.invalid-area", "The waitlist needs an area");
        }
        email = Subscription.normaliseEmail(email);
        source = source == null || source.isBlank() ? "preorder-waitlist" : source.trim();
        if (source.length() > 80) {
            throw new RuleViolation("delivery-runs.invalid-source", "Source is at most 80 characters");
        }
    }

    public static WaitlistEntry join(UUID id, AreaKey area, String email, String source, String unsubscribeToken,
                                     Instant now) {
        return new WaitlistEntry(id, area, email, source, true, unsubscribeToken, now, now, 0);
    }

    public WaitlistEntry rejoin(String newSource, Instant now) {
        return new WaitlistEntry(id, area, email, newSource == null ? source : newSource, true, unsubscribeToken,
                createdAt, now, version);
    }

    public WaitlistEntry leave(Instant now) {
        return new WaitlistEntry(id, area, email, source, false, unsubscribeToken, createdAt, now, version);
    }

    /**
     * A window target serves the entry: a city entry when the target covers that city; a whole-region entry when the
     * target is in that region (any city of it, or all of it).
     */
    public boolean coveredBy(AreaKey target) {
        return area.isCity() ? target.covers(area.country(), area.region(), area.city()) : target.overlaps(area);
    }
}
