package com.afrotransact.content.domain.newsletter;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.EmailAddress;

/**
 * A newsletter subscription with double opt-in.
 *
 * @param id             subscription id (carried in signed links)
 * @param email          normalised address
 * @param status         PENDING, CONFIRMED or UNSUBSCRIBED
 * @param locale         language for emails
 * @param source         where the visitor signed up (footer, checkout, app)
 * @param createdAt      first sign-up
 * @param confirmedAt    when the link was followed
 * @param unsubscribedAt when the visitor left
 * @param updatedAt      last change
 */
public record NewsletterSubscription(UUID id, EmailAddress email, SubscriptionStatus status, String locale,
                                     String source, Instant createdAt, Instant confirmedAt, Instant unsubscribedAt,
                                     Instant updatedAt) {

    public static NewsletterSubscription pending(UUID id, EmailAddress email, String locale, String source,
                                                 Instant now) {
        return new NewsletterSubscription(id, email, SubscriptionStatus.PENDING, locale, source, now, null, null, now);
    }

    /** Sign-up again: pending and unsubscribed addresses get a fresh confirmation; confirmed ones stay. */
    public NewsletterSubscription resubscribe(String newLocale, String newSource, Instant now) {
        if (status == SubscriptionStatus.CONFIRMED) {
            return this;
        }
        return new NewsletterSubscription(id, email, SubscriptionStatus.PENDING, newLocale, newSource, createdAt,
                null, null, now);
    }

    public NewsletterSubscription confirm(Instant now) {
        return switch (status) {
            case CONFIRMED -> this;
            case PENDING -> new NewsletterSubscription(id, email, SubscriptionStatus.CONFIRMED, locale, source,
                    createdAt, now, null, now);
            case UNSUBSCRIBED -> throw new ContentStateConflict("content.subscription-closed",
                    "This subscription was cancelled; sign up again to receive the newsletter");
        };
    }

    public NewsletterSubscription unsubscribe(Instant now) {
        if (status == SubscriptionStatus.UNSUBSCRIBED) {
            return this;
        }
        return new NewsletterSubscription(id, email, SubscriptionStatus.UNSUBSCRIBED, locale, source, createdAt,
                confirmedAt, now, now);
    }

    public boolean needsConfirmation() {
        return status == SubscriptionStatus.PENDING;
    }
}
