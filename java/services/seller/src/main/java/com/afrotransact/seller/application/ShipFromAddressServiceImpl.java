package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipFromAddressServiceImpl implements ShipFromAddressService {

    /** Role-key suffixes that can read (any member) and change ship-from addresses. */
    static final Set<String> READERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support", "ads");
    static final Set<String> EDITORS = Set.of("owner", "admin", "fulfilment");

    private final ShipFromAddressRepository addresses;
    private final OrganisationRepository orgs;
    private final AccessGuard guard;
    private final Clock clock;

    ShipFromAddressServiceImpl(ShipFromAddressRepository addresses, OrganisationRepository orgs, AccessGuard guard,
                               Clock clock) {
        this.addresses = addresses;
        this.orgs = orgs;
        this.guard = guard;
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
        ShipFromAddress next = new ShipFromAddress(id, orgId, in.label(), in.name(), in.company(), in.phone(),
                in.line1(), in.line2(), in.city(), in.state(), in.postalCode(), in.country(), current.isDefault(),
                current.createdAt(), Instant.now(clock), version).normalized();
        if (!addresses.update(next)) {
            throw new SellerStateConflict("seller.concurrent-update",
                    "This address was changed by someone else; reload and try again");
        }
        if (in.makeDefault()) {
            addresses.makeDefault(orgId, id);
        }
        return load(orgId, id);
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
        List<ShipFromAddress> rest = addresses.list(orgId);
        if (gone.isDefault() && !rest.isEmpty()) {
            addresses.makeDefault(orgId, rest.getFirst().id());
            rest = addresses.list(orgId);
        }
        return rest;
    }

    private ShipFromAddress load(String orgId, UUID id) {
        return addresses.find(orgId, id).orElseThrow(() -> new NotFoundException("seller.ship-from-not-found",
                "Ship-from address " + id + " not found"));
    }
}
