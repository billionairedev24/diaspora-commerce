package com.afrotransact.deliveryruns.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.domain.WaitlistEntry;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderWaitlistRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderAudienceServiceImpl implements PreorderAudienceService {

    private static final int ALL = 100_000;

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderWaitlistRepository waitlist;
    private final LocationRepository locations;
    private final SubscriptionRepository subscriptions;
    private final SecretTokens tokens;
    private final RunLinks links;
    private final DeliveryRunsSettings settings;
    private final PreorderOptions options;
    private final DomainEventPublisher events;
    private final PermissionChecker permissions;
    private final Clock clock;

    PreorderAudienceServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                                PreorderWaitlistRepository waitlist, LocationRepository locations,
                                SubscriptionRepository subscriptions, SecretTokens tokens, RunLinks links,
                                DeliveryRunsSettings settings, PreorderOptions options, DomainEventPublisher events,
                                PermissionChecker permissions, Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.waitlist = waitlist;
        this.locations = locations;
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.links = links;
        this.settings = settings;
        this.options = options;
        this.events = events;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void joinWaitlist(String email, String area, String country, String region, String city, String source) {
        AreaKey key = area != null && !area.isBlank() ? AreaKey.parse(area) : new AreaKey(country, region, city);
        String address = Subscription.normaliseEmail(email);
        Instant now = Instant.now(clock);
        Optional<WaitlistEntry> existing = waitlist.find(address, key);
        WaitlistEntry saved;
        if (existing.isPresent()) {
            if (existing.get().active()) {
                return;
            }
            saved = waitlist.update(existing.get().rejoin(source, now));
        } else {
            try {
                saved = waitlist.insert(WaitlistEntry.join(UUID.randomUUID(), key, address, source, tokens.next(),
                        now));
            } catch (DuplicateEntryException e) {
                return;
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entryId", saved.id());
        p.put("area", key.key());
        p.put("country", key.country());
        p.put("region", key.region());
        p.put("city", key.city());
        p.put("email", saved.email());
        p.put("source", saved.source());
        p.put("unsubscribeUrl", unsubscribeUrl(saved.unsubscribeToken()));
        p.put("joinedAt", now);
        events.publish(DeliveryRunsEvents.PREORDER_WAITLIST_JOINED, DeliveryRunsEvents.AGGREGATE,
                saved.id().toString(), p);
    }

    @Override
    @Transactional
    public void leaveWaitlist(String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        WaitlistEntry e = waitlist.findByUnsubscribeToken(token.trim()).orElseThrow(PreorderAudienceServiceImpl::invalidToken);
        if (e.active()) {
            waitlist.update(e.leave(Instant.now(clock)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AudiencePageView audience(UUID windowId, Channel channel, String segment, String after, int limit) {
        permissions.require(DeliveryRunsPermissions.AUDIENCE);
        PreorderWindow w = windows.findById(windowId).orElseThrow(PreorderSupport::notFound);
        if (channel == null) {
            throw new RuleViolation("delivery-runs.invalid-channel", "channel is EMAIL, SMS, PUSH or WHATSAPP");
        }
        String seg = segment == null || segment.isBlank() ? SUBSCRIBERS : segment.trim().toUpperCase(Locale.ROOT);
        List<Member> all = switch (seg) {
            case SUBSCRIBERS -> subscribers(w, channel);
            case WAITLIST -> channel == Channel.EMAIL ? waitlisted(w) : List.of();
            default -> throw new RuleViolation("delivery-runs.invalid-segment", "segment is SUBSCRIBERS or WAITLIST");
        };
        int size = Math.min(Math.max(limit, 1), 1000);
        List<Member> rest = after == null || after.isBlank() ? all
                : all.stream().filter(m -> m.subscriptionId().compareTo(after) > 0).toList();
        List<Member> page = rest.stream().limit(size).toList();
        return new AudiencePageView(page, rest.size() > size ? page.getLast().subscriptionId() : null);
    }

    /** Location subscribers with consent on the channel, then past buyers (each person once), by id. */
    private List<Member> subscribers(PreorderWindow w, Channel channel) {
        List<AreaKey> targets = w.details().targets();
        Map<String, Member> byId = new LinkedHashMap<>();
        Set<String> users = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (Location l : locations.findAll(true)) {
            if (targets.stream().noneMatch(t -> serves(t, l))) {
                continue;
            }
            for (Subscription s : subscriptions.audience(l.id(), channel, null, ALL)) {
                if ((s.userId() != null && !users.add(s.userId())) || (s.email() != null && !emails.add(s.email()))) {
                    continue;
                }
                byId.put(s.id().toString(), new Member(s.id().toString(), s.userId(), s.email(), null,
                        links.unsubscribeUrl(s.unsubscribeToken())));
            }
        }
        for (PreorderWindow other : windows.findPublished()) {
            if (other.details().targets().stream().noneMatch(t -> targets.stream().anyMatch(t::overlaps))) {
                continue;
            }
            for (PreorderOrder o : orders.findByWindow(other.id())) {
                if (!o.counts() || o.customerId() == null || !users.add(o.customerId())
                        || (o.email() != null && emails.contains(o.email()))) {
                    continue;
                }
                String id = "buyer:" + o.customerId();
                byId.put(id, new Member(id, o.customerId(), o.email(), o.phone(), null));
            }
        }
        List<Member> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.subscriptionId().compareTo(b.subscriptionId()));
        return out;
    }

    private List<Member> waitlisted(PreorderWindow w) {
        Set<String> countries = new HashSet<>();
        w.details().targets().forEach(t -> countries.add(t.country()));
        Map<String, Member> byEmail = new LinkedHashMap<>();
        for (String country : countries) {
            for (WaitlistEntry e : waitlist.findActive(country)) {
                if (w.details().targets().stream().anyMatch(e::coveredBy)) {
                    byEmail.putIfAbsent(e.email(), new Member(e.id().toString(), null, e.email(), null,
                            unsubscribeUrl(e.unsubscribeToken())));
                }
            }
        }
        List<Member> out = new ArrayList<>(byEmail.values());
        out.sort((a, b) -> a.subscriptionId().compareTo(b.subscriptionId()));
        return out;
    }

    /** A location serves a target: same market and state, and the target's city when it names one. */
    private static boolean serves(AreaKey target, Location l) {
        String state = l.details().state() == null ? "" : l.details().state();
        return target.country().equalsIgnoreCase(l.details().market()) && target.region().equalsIgnoreCase(state)
                && (!target.isCity() || target.city().equalsIgnoreCase(l.name()));
    }

    private String unsubscribeUrl(String token) {
        return settings.publicWebUrl() + options.pagePath() + "/waitlist/unsubscribe?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static RuleViolation invalidToken() {
        return new RuleViolation("delivery-runs.invalid-token", "This link is not valid");
    }
}
