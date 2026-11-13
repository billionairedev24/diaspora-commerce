package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessCheckers;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A seller can pause and resume their own offer on another seller's page (S382), and only their own: the offer's owner is
 * checked here, whatever the caller asked, and the page's owner and the page itself are never touched.
 */
class OfferPauseResumeTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String ATIN = "AT00000002";

    private final OfferRepository offers = mock(OfferRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final BuyBoxEvaluator evaluator = mock(BuyBoxEvaluator.class);
    private final PermissionChecker permissions = new DefaultPermissionChecker(() -> new Actor("user_b",
            PrincipalType.SELLER_MEMBER, Optional.of("org_b"), Set.of(), Set.of(OffersPermissions.WRITE, OffersPermissions.READ)));
    private final TaxHoldService taxHold = mock(TaxHoldService.class);
    private OfferServiceImpl service;

    @BeforeEach
    void setUp() {
        when(evaluator.view(any())).thenAnswer(i -> {
            OfferView view = mock(OfferView.class);
            when(view.offer()).thenReturn(i.getArgument(0));
            return view;
        });
        when(offers.update(any())).thenAnswer(i -> i.getArgument(0));
        ListingEntitlementsClient entitlements = org -> ListingEntitlementsClient.ListingAllowance.UNLIMITED;
        service = new OfferServiceImpl(offers, catalog, mock(PickupLocationClient.class), entitlements, buyBox,
                evaluator, permissions, ResourceAccessCheckers.rbacOnly(permissions), events,
                new OffersSettings("org_retail", 2, 2, 4, 5), mock(OfferTaxGate.class), taxHold,
                mock(OfferSettingsService.class), mock(OfferTaxMetrics.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Offer offer(String org, OfferStatus status) {
        return new Offer(UUID.randomUUID(), ATIN, "AT00000001", UUID.randomUUID(), null, null, org, SellerKind.SELLER, "SKU-1",
                "SKU-1", OfferCondition.NEW, 999, null, "USD", 0, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), status, true, NOW, NOW, 0);
    }

    @Test
    void theOwnOfferIsPausedAndTheBuyBoxIsRecomputedWithoutTouchingThePage() {
        Offer mine = offer("org_b", OfferStatus.ACTIVE);
        when(offers.findById(mine.id())).thenReturn(Optional.of(mine));

        service.pause(mine.id(), null);

        verify(offers).update(argThat(o -> o.status() == OfferStatus.PAUSED));
        verify(buyBox).recompute(List.of(ATIN), OffersEvents.OFFER_UPDATED);
        verifyNoInteractions(catalog);
    }

    @Test
    void aPausedOwnOfferWithoutAVerifiedTaxBasisWaitsForTheTaxCheckInsteadOfGoingLive() {
        Offer mine = offer("org_b", OfferStatus.PAUSED);
        when(offers.findById(mine.id())).thenReturn(Optional.of(mine));

        service.resume(mine.id(), null);

        verify(offers).update(argThat(o -> o.status() == OfferStatus.TAX_HELD));
        verify(taxHold).releaseFor(ATIN);
        verifyNoInteractions(catalog);
    }

    @Test
    void anotherOrganisationsOfferCannotBePausedOrResumed() {
        Offer theirs = offer("org_a", OfferStatus.ACTIVE);
        when(offers.findById(theirs.id())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.pause(theirs.id(), null)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.resume(theirs.id(), null)).isInstanceOf(ForbiddenActionException.class);

        verify(offers, never()).update(any());
        verifyNoInteractions(catalog);
        assertThat(theirs.status()).isEqualTo(OfferStatus.ACTIVE);
    }
}
