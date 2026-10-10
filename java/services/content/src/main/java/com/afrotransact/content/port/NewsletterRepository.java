package com.afrotransact.content.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.newsletter.NewsletterSubscription;

/** Newsletter subscriptions. */
public interface NewsletterRepository {

    Optional<NewsletterSubscription> findByEmail(EmailAddress email);

    Optional<NewsletterSubscription> findById(UUID id);

    void insert(NewsletterSubscription subscription);

    void update(NewsletterSubscription subscription);
}
