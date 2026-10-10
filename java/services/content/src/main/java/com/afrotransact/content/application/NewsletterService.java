package com.afrotransact.content.application;

import com.afrotransact.content.domain.newsletter.SubscriptionStatus;

/** Newsletter double opt-in. */
public interface NewsletterService {

    /** PENDING (confirmation email requested) or CONFIRMED (already subscribed). */
    SubscriptionStatus subscribe(String email, String locale, String source);

    /** Follows the signed link from the confirmation email. */
    SubscriptionStatus confirm(String token);

    /** Follows the signed unsubscribe link. */
    SubscriptionStatus unsubscribe(String token);
}
