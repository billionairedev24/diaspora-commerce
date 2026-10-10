package com.afrotransact.content.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.newsletter.NewsletterSubscription;
import com.afrotransact.content.domain.newsletter.SubscriptionStatus;
import com.afrotransact.content.domain.newsletter.TokenPurpose;
import com.afrotransact.content.port.NewsletterRepository;
import com.afrotransact.content.port.NewsletterTokens;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterServiceImpl implements NewsletterService {

    private static final Set<String> LOCALES = Set.of("en", "fr", "es", "ar");
    /** Unsubscribe links must keep working for as long as old emails are around. */
    private static final Duration UNSUBSCRIBE_TTL = Duration.ofDays(3650);

    private final NewsletterRepository subscriptions;
    private final NewsletterTokens tokens;
    private final DomainEventPublisher events;
    private final ContentSettings settings;
    private final Clock clock;

    NewsletterServiceImpl(NewsletterRepository subscriptions, NewsletterTokens tokens, DomainEventPublisher events,
                          ContentSettings settings, Clock clock) {
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubscriptionStatus subscribe(String email, String locale, String source) {
        EmailAddress address = EmailAddress.parse(email, "email");
        String loc = locale == null ? "en" : locale.trim().toLowerCase(Locale.ROOT);
        loc = LOCALES.contains(loc) ? loc : "en";
        String src = source == null || source.isBlank() ? "footer" : source.trim();
        src = src.length() > 40 ? src.substring(0, 40) : src;
        Instant now = Instant.now(clock);
        Optional<NewsletterSubscription> existing = subscriptions.findByEmail(address);
        NewsletterSubscription sub;
        if (existing.isPresent()) {
            sub = existing.get().resubscribe(loc, src, now);
            if (!sub.needsConfirmation()) {
                return sub.status();
            }
            subscriptions.update(sub);
        } else {
            sub = NewsletterSubscription.pending(UUID.randomUUID(), address, loc, src, now);
            subscriptions.insert(sub);
        }
        Instant expires = now.plus(settings.newsletterTokenTtl());
        events.publish(ContentEvents.NEWSLETTER_CONFIRMATION_REQUESTED, ContentEvents.AGGREGATE,
                sub.id().toString(), new ContentEventPayloads.NewsletterConfirmationRequested(sub.id(),
                        address.value(), sub.locale(),
                        link(settings.newsletterConfirmUrl(), tokens.issue(sub.id(), TokenPurpose.CONFIRM, expires)),
                        unsubscribeLink(sub.id(), now), expires));
        return SubscriptionStatus.PENDING;
    }

    @Override
    @Transactional
    public SubscriptionStatus confirm(String token) {
        Instant now = Instant.now(clock);
        NewsletterSubscription sub = verified(token, TokenPurpose.CONFIRM, now);
        NewsletterSubscription confirmed = sub.confirm(now);
        if (confirmed != sub) {
            subscriptions.update(confirmed);
            events.publish(ContentEvents.NEWSLETTER_SUBSCRIBED, ContentEvents.AGGREGATE, sub.id().toString(),
                    new ContentEventPayloads.NewsletterStatusChanged(sub.id(), sub.email().value(), sub.locale(),
                            unsubscribeLink(sub.id(), now)));
        }
        return confirmed.status();
    }

    @Override
    @Transactional
    public SubscriptionStatus unsubscribe(String token) {
        Instant now = Instant.now(clock);
        NewsletterSubscription sub = verified(token, TokenPurpose.UNSUBSCRIBE, now);
        NewsletterSubscription left = sub.unsubscribe(now);
        if (left != sub) {
            subscriptions.update(left);
            events.publish(ContentEvents.NEWSLETTER_UNSUBSCRIBED, ContentEvents.AGGREGATE, sub.id().toString(),
                    new ContentEventPayloads.NewsletterStatusChanged(sub.id(), sub.email().value(), sub.locale(), null));
        }
        return left.status();
    }

    private NewsletterSubscription verified(String token, TokenPurpose purpose, Instant now) {
        return tokens.verify(token, now)
                .filter(c -> c.purpose() == purpose)
                .flatMap(c -> subscriptions.findById(c.subscriptionId()))
                .orElseThrow(() -> new ContentRuleViolation("content.invalid-token",
                        "This link is invalid or has expired; sign up again to get a new one",
                        Map.of("token", "Invalid or expired")));
    }

    private String unsubscribeLink(UUID id, Instant now) {
        return link(settings.newsletterUnsubscribeUrl(), tokens.issue(id, TokenPurpose.UNSUBSCRIBE, now.plus(UNSUBSCRIBE_TTL)));
    }

    private static String link(String base, String token) {
        return base + (base.contains("?") ? "&" : "?") + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}
