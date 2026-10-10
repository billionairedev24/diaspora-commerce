package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.offers.domain.SellerStanding;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.SellerStandingRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerStandingServiceImpl implements SellerStandingService {

    static final String CONSUMER = "offers-seller";

    private final SellerStandingRepository standings;
    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final PermissionChecker permissions;
    private final Clock clock;

    SellerStandingServiceImpl(SellerStandingRepository standings, OfferRepository offers, BuyBoxService buyBox,
                              InboxDeduplicator inbox, PermissionChecker permissions, Clock clock) {
        this.standings = standings;
        this.offers = offers;
        this.buyBox = buyBox;
        this.inbox = inbox;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void listingsVisibility(UUID eventId, String orgId, boolean hidden) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        SellerStanding current = standings.find(orgId).orElse(SellerStanding.unknown(orgId));
        if (current.listingsHidden() == hidden && current.updatedAt() != null) {
            return;
        }
        standings.save(current.withListingsHidden(hidden, Instant.now(clock)));
        buyBox.recompute(offers.openAtinsOfSeller(orgId), hidden ? "seller.listings.hidden"
                : "seller.listings.restored");
    }

    @Override
    @Transactional
    public void orgProfile(UUID eventId, String orgId, String displayName) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || displayName == null || displayName.isBlank()) {
            return;
        }
        SellerStanding current = standings.find(orgId).orElse(SellerStanding.unknown(orgId));
        standings.save(current.withDisplayName(displayName.trim(), Instant.now(clock)));
    }

    @Override
    @Transactional
    public SellerStanding updatePerformance(String orgId, SellerPerformanceCommand cmd) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.staff-only", "Only staff or the seller-health job can set "
                    + "seller performance");
        }
        SellerStanding current = standings.find(orgId).orElse(SellerStanding.unknown(orgId));
        SellerStanding saved = standings.save(current.withPerformance(cmd.rating(), cmd.onTimeRate(),
                cmd.defectRate(), cmd.restricted(), Instant.now(clock)));
        buyBox.recompute(offers.openAtinsOfSeller(orgId), "seller.performance.updated");
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public SellerStanding get(String orgId) {
        permissions.requireForOrg(OffersPermissions.READ, orgId);
        return standings.find(orgId).orElse(SellerStanding.unknown(orgId));
    }
}
