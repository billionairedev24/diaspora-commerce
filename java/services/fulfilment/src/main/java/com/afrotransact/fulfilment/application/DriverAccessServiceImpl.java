package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.AccessSource;
import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.DriverAccessStatus;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.DriverAccessRepository;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverAccessServiceImpl implements DriverAccessService {

    static final String CONSUMER = "fulfilment.identity";
    static final String DRIVER_ROLE = "platform.driver";

    private final FulfilmentAccess access;
    private final DriverAccessRepository drivers;
    private final RouteRepository routes;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final Clock clock;

    DriverAccessServiceImpl(FulfilmentAccess access, DriverAccessRepository drivers, RouteRepository routes,
                            InboxDeduplicator inbox, DomainEventPublisher events, DispatchFeed feed, Clock clock) {
        this.access = access;
        this.drivers = drivers;
        this.routes = routes;
        this.inbox = inbox;
        this.events = events;
        this.feed = feed;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DriverSession check(DriverCredential credential) {
        DriverContext ctx = resolve(credential);
        if (ctx.signOut()) {
            throw revoked(ctx.access());
        }
        Actor actor = access.current();
        return new DriverSession(ctx.driverId(), DriverAccessStatus.ACTIVE.name(),
                actor.permissions().stream().sorted().toList(),
                routes.openForDriver(ctx.driverId()).stream().map(DeliveryRoute::id).toList(), Instant.now(clock));
    }

    @Override
    @Transactional
    public DriverContext resolve(DriverCredential credential) {
        Actor actor = access.current();
        Instant now = Instant.now(clock);
        Instant issuedAt = credential == null || credential.issuedAt() == null ? now : credential.issuedAt();
        Optional<DriverAccess> known = drivers.find(actor.subject());
        if (actor.can(FulfilmentPermissions.DELIVERY)) {
            if (known.isPresent() && known.get().revokedFor(issuedAt)) {
                return new DriverContext(actor.subject(), known.get(), true);
            }
            if (known.isEmpty() || known.get().status() == DriverAccessStatus.REVOKED) {
                DriverAccess active = new DriverAccess(actor.subject(), DriverAccessStatus.ACTIVE, issuedAt,
                        "token grants delivery:operate", AccessSource.TOKEN);
                drivers.apply(active);
                return new DriverContext(actor.subject(), active, false);
            }
            return new DriverContext(actor.subject(), known.get(), false);
        }
        if (known.isEmpty()) {
            throw new DriverAccessDenied("fulfilment.no-driver-access", "You are signed in, but don't have the "
                    + "“Use the driver app” permission (delivery:operate). " + DriverAccessDenied.GRANT_HINT, false,
                    null);
        }
        DriverAccess state = known.get();
        if (state.status() == DriverAccessStatus.ACTIVE) {
            // The new token no longer carries delivery:operate: identity revoked it before issuing the token.
            state = new DriverAccess(actor.subject(), DriverAccessStatus.REVOKED, issuedAt,
                    "token no longer grants delivery:operate", AccessSource.TOKEN);
            if (drivers.apply(state)) {
                announceRevocation(state, now);
            }
        }
        return new DriverContext(actor.subject(), state, true);
    }

    @Override
    @Transactional
    public boolean onIdentityChange(UUID eventId, IdentityAccessChange c) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || c.userId() == null || c.userId().isBlank()) {
            return false;
        }
        Instant at = c.at() == null ? Instant.now(clock) : c.at();
        boolean driverRole = DRIVER_ROLE.equals(c.roleKey());
        boolean deliveryPermission = FulfilmentPermissions.DELIVERY.equals(c.permissionKey());
        boolean known = drivers.find(c.userId()).isPresent();
        DriverAccessStatus next = switch (c.type()) {
            case "identity.role.revoked" -> driverRole ? DriverAccessStatus.REVOKED : null;
            case "identity.permission.revoked" -> deliveryPermission ? DriverAccessStatus.REVOKED : null;
            case "identity.user.disabled" -> known ? DriverAccessStatus.REVOKED : null;
            case "identity.role.granted" -> driverRole ? DriverAccessStatus.ACTIVE : null;
            case "identity.permission.granted" -> deliveryPermission ? DriverAccessStatus.ACTIVE : null;
            case "identity.user.enabled" -> known ? DriverAccessStatus.ACTIVE : null;
            default -> null;
        };
        if (next == null) {
            return false;
        }
        String why = c.type() + (c.reason() == null || c.reason().isBlank() ? "" : ": " + c.reason());
        DriverAccess state = new DriverAccess(c.userId(), next, at, why, AccessSource.IDENTITY);
        boolean applied = drivers.apply(state);
        if (applied && next == DriverAccessStatus.REVOKED) {
            announceRevocation(state, Instant.now(clock));
        }
        return applied;
    }

    @Override
    @Transactional
    public DriverAccess suspend(String driverId, String reason) {
        Actor by = access.dispatcher();
        Instant now = Instant.now(clock);
        String why = "suspended by " + by.subject() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        DriverAccess state = new DriverAccess(driverId, DriverAccessStatus.REVOKED, now, why, AccessSource.DISPATCH);
        drivers.apply(state);
        announceRevocation(state, now);
        return state;
    }

    @Override
    @Transactional
    public DriverAccess reinstate(String driverId) {
        Actor by = access.dispatcher();
        DriverAccess state = new DriverAccess(driverId, DriverAccessStatus.ACTIVE, Instant.now(clock),
                "reinstated by " + by.subject(), AccessSource.DISPATCH);
        drivers.apply(state);
        return state;
    }

    private void announceRevocation(DriverAccess state, Instant now) {
        events.publish(FulfilmentEvents.ACCESS_REVOKED, FulfilmentEvents.AGGREGATE, state.driverId(),
                new FulfilmentEventPayloads.AccessRevoked(state.driverId(), state.reason(), state.changedAt()));
        List<String> routeIds = routes.openForDriver(state.driverId()).stream().map(DeliveryRoute::id).toList();
        feed.publish(new DispatchFeed.Update("session.revoked", state.driverId(), null, "HIGH",
                Map.of("driverId", state.driverId(), "revokedAt", state.changedAt().toString(),
                        "reason", state.reason(), "openRoutes", routeIds,
                        "message", "Your driver access was removed. Unsynced events captured before "
                                + state.changedAt() + " are still accepted."), now));
    }

    static DriverAccessDenied revoked(DriverAccess state) {
        return new DriverAccessDenied("fulfilment.driver-access-revoked", "Your driver access was removed ("
                + state.reason() + "). Sign out; unsynced events captured before the revocation are kept and "
                + "accepted by POST /v1/driver/sync.", true, state.changedAt());
    }
}
