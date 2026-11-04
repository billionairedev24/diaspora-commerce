package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.domain.SubscriptionStatus;
import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.SubscriberCounts;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final LocationRepository locations;
    private final AttributionService attribution;
    private final DeliveryRunsEventPublisher events;
    private final SecretTokens tokens;
    private final RunLinks links;
    private final DeliveryRunsSettings settings;
    private final ActorResolver actors;
    private final PermissionChecker permissions;
    private final Clock clock;

    SubscriptionServiceImpl(SubscriptionRepository subscriptions, LocationRepository locations,
                            AttributionService attribution, DeliveryRunsEventPublisher events, SecretTokens tokens,
                            RunLinks links,
                            DeliveryRunsSettings settings, ActorResolver actors, PermissionChecker permissions,
                            Clock clock) {
        this.subscriptions = subscriptions;
        this.locations = locations;
        this.attribution = attribution;
        this.events = events;
        this.tokens = tokens;
        this.links = links;
        this.settings = settings;
        this.actors = actors;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Subscription subscribeMine(UUID locationId, Set<Channel> channels, String source) {
        return subscribeMine(locationId, channels, source, null, null);
    }

    @Override
    @Transactional
    public Subscription subscribeMine(UUID locationId, Set<Channel> channels, String source, UtmTags utm,
                                      UUID runId) {
        Actor actor = actors.current();
        Location location = activeLocation(locationId);
        Instant now = Instant.now(clock);
        Optional<Subscription> existing = subscriptions.findByUser(locationId, actor.subject());
        if (existing.isEmpty()) {
            Subscription created = subscriptions.insert(Subscription.forUser(UUID.randomUUID(), locationId,
                    actor.subject(), channels, source, tokens.next(), now));
            events.subscription(DeliveryRunsEvents.SUBSCRIPTION_CREATED, location, created, channels,
                    source(source), null);
            attribution.signupRequested(created.id(), locationId, runId, utm, true);
            return created;
        }
        Subscription before = existing.get();
        Set<Channel> added = EnumSet.noneOf(Channel.class);
        added.addAll(channels == null ? Set.of() : channels);
        added.removeAll(before.activeChannels());
        Subscription after = subscriptions.update(before.grant(channels, source, now));
        if (before.activeChannels().isEmpty() && !after.activeChannels().isEmpty()) {
            attribution.signupRequested(after.id(), locationId, runId, utm, true);
        }
        if (!added.isEmpty()) {
            events.subscription(DeliveryRunsEvents.SUBSCRIPTION_CREATED, location, after, added, source(source), null);
        }
        return after;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Subscription> mine() {
        return subscriptions.findAllByUser(actors.current().subject());
    }

    @Override
    @Transactional
    public Subscription unsubscribeMine(UUID locationId, Set<Channel> channels) {
        Actor actor = actors.current();
        Subscription s = subscriptions.findByUser(locationId, actor.subject())
                .orElseThrow(() -> new NotFoundException("delivery-runs.subscription-not-found",
                        "You are not subscribed to this location"));
        return withdraw(s, channels, "account");
    }

    @Override
    @Transactional
    public void subscribeEmail(UUID locationId, String email, String source) {
        subscribeEmail(locationId, email, source, null, null);
    }

    @Override
    @Transactional
    public void subscribeEmail(UUID locationId, String email, String source, UtmTags utm, UUID runId) {
        Location location = activeLocation(locationId);
        String address = Subscription.normaliseEmail(email);
        Instant now = Instant.now(clock);
        String token = tokens.next();
        Instant expires = now.plus(settings.confirmationTtl());
        Optional<Subscription> existing = subscriptions.findByEmail(locationId, address);
        Subscription saved;
        if (existing.isEmpty()) {
            saved = subscriptions.insert(Subscription.forEmail(UUID.randomUUID(), locationId, address, source,
                    tokens.hash(token), expires, tokens.next(), now));
        } else if (existing.get().status() == SubscriptionStatus.ACTIVE) {
            return;
        } else {
            saved = subscriptions.update(existing.get().requestConfirmation(tokens.hash(token), expires, source, now));
        }
        attribution.signupRequested(saved.id(), locationId, runId, utm, false);
        events.subscription(DeliveryRunsEvents.SUBSCRIPTION_CREATED, location, saved, EnumSet.of(Channel.EMAIL),
                source(source), token);
    }

    @Override
    @Transactional
    public Subscription confirm(String token) {
        Subscription s = byConfirmToken(token);
        if (s.status() == SubscriptionStatus.ACTIVE) {
            return s;
        }
        Subscription confirmed = subscriptions.update(s.confirm(Instant.now(clock)));
        attribution.signupActivated(confirmed.id(), confirmed.locationId());
        events.subscription(DeliveryRunsEvents.SUBSCRIPTION_CONFIRMED, location(confirmed.locationId()), confirmed,
                confirmed.activeChannels(), "double-opt-in", null);
        return confirmed;
    }

    @Override
    @Transactional
    public Subscription unsubscribe(String token, Set<Channel> channels) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        Subscription s = subscriptions.findByUnsubscribeToken(token).orElseThrow(SubscriptionServiceImpl::invalidToken);
        return withdraw(s, channels, "unsubscribe-link");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriberCounts> counts(UUID locationId) {
        permissions.require(DeliveryRunsPermissions.READ);
        return subscriptions.counts(locationId);
    }

    @Override
    @Transactional(readOnly = true)
    public AudiencePage audience(UUID locationId, Channel channel, UUID after, int limit) {
        permissions.require(DeliveryRunsPermissions.AUDIENCE);
        if (channel == null) {
            throw new RuleViolation("delivery-runs.channel-required", "channel is required");
        }
        int size = Math.min(Math.max(limit, 1), 1000);
        List<Subscription> page = subscriptions.audience(locationId, channel, after, size);
        List<AudiencePage.Member> members = page.stream()
                .map(s -> new AudiencePage.Member(s.id(), s.userId(), s.email(),
                        links.unsubscribeUrl(s.unsubscribeToken())))
                .toList();
        return new AudiencePage(members, page.size() == size ? page.getLast().id() : null);
    }

    private Subscription withdraw(Subscription s, Set<Channel> channels, String source) {
        Set<Channel> before = s.consents().values().stream().filter(c -> c.granted()).map(c -> c.channel())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Channel.class)));
        Subscription after = subscriptions.update(s.withdraw(channels, Instant.now(clock)));
        Set<Channel> removed = EnumSet.noneOf(Channel.class);
        removed.addAll(before);
        after.consents().values().stream().filter(c -> c.granted()).forEach(c -> removed.remove(c.channel()));
        if (!removed.isEmpty()) {
            events.subscription(DeliveryRunsEvents.SUBSCRIPTION_CANCELLED, location(after.locationId()), after,
                    removed, source, null);
        }
        return after;
    }

    private Subscription byConfirmToken(String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        return subscriptions.findByConfirmTokenHash(tokens.hash(token))
                .orElseThrow(SubscriptionServiceImpl::invalidToken);
    }

    private Location activeLocation(UUID id) {
        return locations.findById(id).filter(Location::active)
                .orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found", "Location not found"));
    }

    private Location location(UUID id) {
        return locations.findById(id).orElseThrow();
    }

    private static String source(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    private static NotFoundException invalidToken() {
        return new NotFoundException("delivery-runs.invalid-token", "This link is invalid or was already used");
    }
}
