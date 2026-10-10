package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.AfrotransactPickup;
import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.DeliveryZones;
import com.afrotransact.seller.port.Geocoder;
import com.afrotransact.seller.port.GeoUnavailableException;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipFromAddressServiceImpl implements ShipFromAddressService {

    private static final Logger LOG = LoggerFactory.getLogger(ShipFromAddressServiceImpl.class);

    /** Role-key suffixes that can read (any member) and change ship-from addresses. */
    static final Set<String> READERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support", "ads");
    static final Set<String> EDITORS = Set.of("owner", "admin", "fulfilment");

    private final ShipFromAddressRepository addresses;
    private final OrganisationRepository orgs;
    private final AccessGuard guard;
    private final Geocoder geocoder;
    private final DeliveryZones zones;
    private final DomainEventPublisher events;
    private final SellerSettings settings;
    private final Clock clock;

    ShipFromAddressServiceImpl(ShipFromAddressRepository addresses, OrganisationRepository orgs, AccessGuard guard,
                               Geocoder geocoder, DeliveryZones zones, DomainEventPublisher events,
                               SellerSettings settings, Clock clock) {
        this.addresses = addresses;
        this.orgs = orgs;
        this.guard = guard;
        this.geocoder = geocoder;
        this.zones = zones;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipFromAddress> list(String orgId) {
        guard.orgRole(orgId, READERS, STAFF_PERMISSION);
        return addresses.list(orgId);
    }

    @Override
    @Transactional
    public ShipFromAddress create(String orgId, ShipFromAddressInput in) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        if (orgs.findById(orgId).isEmpty()) {
            throw new NotFoundException("seller.org-not-found", "Organisation " + orgId + " not found");
        }
        List<ShipFromAddress> existing = addresses.list(orgId);
        if (existing.size() >= ShipFromAddress.MAX_PER_ORG) {
            throw new SellerRuleViolation("seller.too-many-ship-from", "Keep at most "
                    + ShipFromAddress.MAX_PER_ORG + " ship-from addresses; delete one first");
        }
        Instant now = Instant.now(clock);
        ShipFromAddress a = new ShipFromAddress(UUID.randomUUID(), orgId, in.label(), in.name(), in.company(),
                in.phone(), in.line1(), in.line2(), in.city(), in.state(), in.postalCode(), in.country(), false, now,
                now, 0).normalized();
        a = a.withPosition(tryGeocode(a).orElse(null));
        addresses.insert(a);
        if (existing.isEmpty() || in.makeDefault()) {
            addresses.makeDefault(orgId, a.id());
        }
        return load(orgId, a.id());
    }

    @Override
    @Transactional
    public ShipFromAddress update(String orgId, UUID id, ShipFromAddressInput in) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        ShipFromAddress current = load(orgId, id);
        long version = in.version() == null ? current.version() : in.version();
        Instant now = Instant.now(clock);
        ShipFromAddress next = new ShipFromAddress(id, orgId, in.label(), in.name(), in.company(), in.phone(),
                in.line1(), in.line2(), in.city(), in.state(), in.postalCode(), in.country(), current.isDefault(),
                current.createdAt(), now, version, current.position(), current.pickup()).normalized();
        boolean moved = next.movedFrom(current);
        if (moved || next.position() == null) {
            if (next.pickup().enabled()) {
                // A pickup location must stay geocoded and inside the delivery area: geo must answer.
                ShipFromAddress located = next.withPosition(geocodeForPickup(next));
                next = located.withPickup(checkedPickup(located, next.pickup(), now), now);
            } else {
                next = next.withPosition(tryGeocode(next).orElse(null));
            }
        }
        if (!addresses.update(next)) {
            throw new SellerStateConflict("seller.concurrent-update",
                    "This address was changed by someone else; reload and try again");
        }
        if (in.makeDefault()) {
            addresses.makeDefault(orgId, id);
        }
        ShipFromAddress saved = load(orgId, id);
        if (moved && saved.pickup().enabled()) {
            publish(saved, false, now);
        }
        return saved;
    }

    @Override
    @Transactional
    public List<ShipFromAddress> makeDefault(String orgId, UUID id) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        load(orgId, id);
        addresses.makeDefault(orgId, id);
        return addresses.list(orgId);
    }

    @Override
    @Transactional
    public List<ShipFromAddress> delete(String orgId, UUID id) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        ShipFromAddress gone = load(orgId, id);
        addresses.delete(orgId, id);
        if (gone.pickup().enabled()) {
            publish(gone, true, Instant.now(clock));
        }
        List<ShipFromAddress> rest = addresses.list(orgId);
        if (gone.isDefault() && !rest.isEmpty()) {
            addresses.makeDefault(orgId, rest.getFirst().id());
            rest = addresses.list(orgId);
        }
        return rest;
    }

    @Override
    @Transactional
    public ShipFromAddress setPickup(String orgId, UUID id, PickupInput in) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        ShipFromAddress current = load(orgId, id);
        Instant now = Instant.now(clock);
        long version = in.version() == null ? current.version() : in.version();
        ShipFromAddress next;
        if (in.enabled()) {
            Organisation org = orgs.findById(orgId).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                    "Organisation " + orgId + " not found"));
            if (org.status() != OrgStatus.APPROVED) {
                throw new SellerStateConflict("seller.org-not-approved", "AfroTransact pickup can be enabled once "
                        + "the organisation is approved (it is " + org.status() + ")");
            }
            GeoPosition position = current.position() != null && current.position().streetLevel()
                    ? current.position() : geocodeForPickup(current);
            ShipFromAddress located = current.withPosition(position);
            AfrotransactPickup requested = new AfrotransactPickup(true, in.hours(), null,
                    current.pickup().enabled() ? current.pickup().enabledAt() : now);
            next = located.withPickup(checkedPickup(located, requested, now), now);
        } else {
            next = current.withPickup(new AfrotransactPickup(false, in.hours().isEmpty() ? current.pickup().hours()
                    : in.hours(), null, null), now);
        }
        next = new ShipFromAddress(next.id(), next.orgId(), next.label(), next.name(), next.company(), next.phone(),
                next.line1(), next.line2(), next.city(), next.state(), next.postalCode(), next.country(),
                next.isDefault(), next.createdAt(), now, version, next.position(), next.pickup());
        if (!addresses.update(next)) {
            throw new SellerStateConflict("seller.concurrent-update",
                    "This address was changed by someone else; reload and try again");
        }
        ShipFromAddress saved = load(orgId, id);
        if (saved.pickup().enabled() || current.pickup().enabled()) {
            publish(saved, false, now);
        }
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public ShipFromAddress location(UUID id) {
        Actor actor = guard.current();
        Optional<ShipFromAddress> found = addresses.findById(id);
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(SERVICE_READ);
        if (!service) {
            String org = found.map(ShipFromAddress::orgId).orElse(actor.orgId().orElse(""));
            guard.orgRole(org, READERS, STAFF_PERMISSION);
        }
        return found.orElseThrow(() -> new NotFoundException("seller.ship-from-not-found",
                "Ship-from location " + id + " not found"));
    }

    /** Inside an active DELIVERY zone, or within the configured radius of one; records the zone. */
    private AfrotransactPickup checkedPickup(ShipFromAddress located, AfrotransactPickup requested, Instant now) {
        GeoPosition p = located.position();
        Optional<DeliveryZones.ZoneMatch> zone = zones.nearest(p.lat(), p.lng(), settings.pickupMaxRadiusMiles());
        if (zone.isEmpty()) {
            throw new SellerRuleViolation("seller.pickup-outside-delivery-area", "AfroTransact Delivery doesn't "
                    + "collect from " + located.city() + " " + located.postalCode() + " yet: it is not inside, or "
                    + "within " + settings.pickupMaxRadiusMiles() + " miles of, an AfroTransact delivery zone",
                    Map.of("afrotransactPickup", "Outside the AfroTransact delivery area"));
        }
        return new AfrotransactPickup(true, requested.hours(), zone.get().name(),
                requested.enabledAt() == null ? now : requested.enabledAt());
    }

    /** Geocodes for a pickup location: geo must answer with a street-level position. */
    private GeoPosition geocodeForPickup(ShipFromAddress a) {
        GeoPosition position = geocoder.geocode(a).orElseThrow(() -> new SellerRuleViolation(
                "seller.ship-from-not-geocoded", "We couldn't locate " + a.line1() + ", " + a.city()
                + "; check the address", Map.of("line1", "Address not found")));
        if (!position.streetLevel()) {
            throw new SellerRuleViolation("seller.ship-from-not-geocoded", "We could only locate the ZIP code of "
                    + a.line1() + ", " + a.city() + "; a driver needs the street address",
                    Map.of("line1", "Street address not found"));
        }
        return position;
    }

    /** Best effort: an address is saved even when geo is down; it is geocoded again on the next change. */
    private Optional<GeoPosition> tryGeocode(ShipFromAddress a) {
        try {
            return geocoder.geocode(a);
        } catch (GeoUnavailableException e) {
            LOG.warn("Ship-from {} saved without position: {}", a.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private void publish(ShipFromAddress a, boolean deleted, Instant now) {
        events.publish(SellerEvents.PICKUP_LOCATION_UPDATED, SellerEvents.AGGREGATE_ORG, a.orgId(),
                PickupLocationPayload.of(a, deleted, now));
    }

    private ShipFromAddress load(String orgId, UUID id) {
        return addresses.find(orgId, id).orElseThrow(() -> new NotFoundException("seller.ship-from-not-found",
                "Ship-from address " + id + " not found"));
    }
}
