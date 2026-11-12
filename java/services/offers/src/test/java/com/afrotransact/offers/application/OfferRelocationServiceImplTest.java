package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.application.OfferRelocationService.Merged;
import com.afrotransact.offers.application.OfferRelocationService.VariantMoved;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S386: the offers follow a page merge, and (S385) a variant staff moved or split. A moved offer is held for tax until the
 * seller's decision is confirmed again, never live on the strength of the old page; nothing is moved twice.
 */
class OfferRelocationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID SURVIVOR = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    private final OfferRepository offers = mock(OfferRepository.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final InboxDeduplicator inbox = mock(InboxDeduplicator.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private OfferRelocationServiceImpl service;

    @BeforeEach
    void setUp() {
        when(inbox.firstDelivery(anyString(), any())).thenReturn(true);
        when(offers.relocate(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.update(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.findOpenByProductAtin(anyString())).thenReturn(List.of());
        when(offers.findOpenByAtins(anyCollection())).thenReturn(List.of());
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
        service = new OfferRelocationServiceImpl(offers, buyBox, inbox, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- a merge --------------------------------------------------------------------------------------------------

    @Test
    void anOpenOfferMovesToTheMappedVariantAndWaitsAsTaxHeldBecauseItIsANewAtin() {
        Offer offer = offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(offer));

        int moved = service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));

        assertThat(moved).isEqualTo(1);
        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).relocate(stored.capture());
        Offer relocated = stored.getValue();
        assertThat(relocated.atin()).isEqualTo("AT10000002");
        assertThat(relocated.productAtin()).isEqualTo("AT10000001");
        assertThat(relocated.productId()).isEqualTo(SURVIVOR);
        assertThat(relocated.categoryId()).isEqualTo(CATEGORY);
        assertThat(relocated.status()).as("never live on the old ATIN's tax decision").isEqualTo(OfferStatus.TAX_HELD);
        assertThat(relocated.heldReason()).isEqualTo(Offer.HOLD_UNVERIFIED);
        assertThat(relocated.taxBasis()).isNull();
        verify(events).publish(eq(OffersEvents.OFFER_UPDATED), eq(OffersEvents.AGGREGATE), eq("AT10000002"), any());
        verify(buyBox).recompute(Set.of("AT20000002", "AT10000002"), "catalog.product.merged");
    }

    @Test
    void aSellerWhoAlreadySellsItOnTheSurvivingPageKeepsThatOfferAndTheMovedOneIsWithdrawn() {
        Offer offer = offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(offer));
        when(offers.findOpen("org_b", "AT10000002", OfferCondition.NEW, false))
                .thenReturn(Optional.of(offer("org_b", "SKU-OLD", "AT10000002", "AT10000001", OfferStatus.ACTIVE)));

        int moved = service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));

        assertThat(moved).isZero();
        verify(offers, never()).relocate(any());
        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).update(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(OfferStatus.WITHDRAWN);
        verify(events).publish(eq(OffersEvents.OFFER_WITHDRAWN), eq(OffersEvents.AGGREGATE), any(), any());
    }

    @Test
    void anOfferThatCannotBeMovedBecauseTheSkuIsTakenThereIsWithdrawnNotLost() {
        Offer offer = offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(offer));
        when(offers.relocate(any())).thenThrow(new ConflictException("offers.offer-exists", "The seller already has one"));
        when(offers.findById(offer.id())).thenReturn(Optional.of(offer));

        int moved = service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));

        assertThat(moved).isZero();
        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).update(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    @Test
    void aPausedOfferStaysPausedWhenItMoves() {
        Offer offer = offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.PAUSED);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(offer));

        service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));

        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).relocate(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(OfferStatus.PAUSED);
    }

    @Test
    void theMovedOfferIsLiveOnlyAsFarAsTheSurvivingPageIs() {
        Offer offer = offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(offer));

        service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));
        ArgumentCaptor<Offer> notLive = ArgumentCaptor.forClass(Offer.class);
        verify(offers).relocate(notLive.capture());
        assertThat(notLive.getValue().productLive()).isFalse();

        Offer liveOnSurvivor = offer("org_c", "SKU-C", "AT10000009", "AT10000001", OfferStatus.ACTIVE);
        when(offers.findOpenByProductAtin("AT10000001")).thenReturn(List.of(liveOnSurvivor));
        Offer second = offer("org_d", "SKU-D", "AT20000002", "AT20000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT20000002"))).thenReturn(List.of(second));
        service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")));
        ArgumentCaptor<Offer> live = ArgumentCaptor.forClass(Offer.class);
        verify(offers, times(2)).relocate(live.capture());
        assertThat(live.getAllValues().get(1).productLive()).isTrue();
    }

    @Test
    void aMergeEventDeliveredTwiceMovesNothingTheSecondTime() {
        UUID eventId = UUID.randomUUID();
        when(inbox.firstDelivery(OfferRelocationServiceImpl.CONSUMER, eventId)).thenReturn(true, false);
        when(offers.findOpenByAtins(List.of("AT20000002")))
                .thenReturn(List.of(offer("org_b", "SKU-B", "AT20000002", "AT20000001", OfferStatus.ACTIVE)));

        assertThat(service.pagesMerged(eventId, merged(Map.of("AT20000002", "AT10000002")))).isEqualTo(1);
        assertThat(service.pagesMerged(eventId, merged(Map.of("AT20000002", "AT10000002")))).isZero();
        verify(offers, times(1)).relocate(any());
    }

    @Test
    void aMergeWithNoOffersChangesNothingAndDoesNotTouchTheBuyBox() {
        assertThat(service.pagesMerged(UUID.randomUUID(), merged(Map.of("AT20000002", "AT10000002")))).isZero();

        verify(buyBox, never()).recompute(any(), any());
        verify(events, never()).publish(any(), any(), any(), any());
    }

    // ---- a variant moved or split ---------------------------------------------------------------------------------

    @Test
    void aMovedVariantKeepsItsAtinAndItsOffersFollowThePageAndWaitForTheTaxCheck() {
        Offer offer = offer("org_b", "SKU-B", "AT10000003", "AT10000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT10000003"))).thenReturn(List.of(offer));

        int followed = service.variantMoved(UUID.randomUUID(),
                new VariantMoved("AT10000003", SURVIVOR, "AT20000001", CATEGORY, true));

        assertThat(followed).isEqualTo(1);
        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).relocate(stored.capture());
        Offer moved = stored.getValue();
        assertThat(moved.atin()).isEqualTo("AT10000003");
        assertThat(moved.productAtin()).isEqualTo("AT20000001");
        assertThat(moved.productId()).isEqualTo(SURVIVOR);
        assertThat(moved.categoryId()).isEqualTo(CATEGORY);
        assertThat(moved.productLive()).isTrue();
        assertThat(moved.status()).isEqualTo(OfferStatus.TAX_HELD);
        verify(events).publish(eq(OffersEvents.OFFER_UPDATED), eq(OffersEvents.AGGREGATE), eq("AT10000003"), any());
        verify(buyBox).recompute(Set.of("AT10000003"), "catalog.variant.moved");
    }

    @Test
    void aSplitOntoAPageInReviewIsNotLive() {
        Offer offer = offer("org_b", "SKU-B", "AT10000003", "AT10000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT10000003"))).thenReturn(List.of(offer));

        service.variantMoved(UUID.randomUUID(), new VariantMoved("AT10000003", SURVIVOR, "AT20000001", null, false));

        ArgumentCaptor<Offer> stored = ArgumentCaptor.forClass(Offer.class);
        verify(offers).relocate(stored.capture());
        assertThat(stored.getValue().productLive()).isFalse();
        assertThat(stored.getValue().categoryId()).as("kept when the event names none").isEqualTo(offer.categoryId());
    }

    @Test
    void theOwnOfferOfAMovedVariantIsNeverWithdrawnAsADuplicateOfItself() {
        Offer offer = offer("org_b", "SKU-B", "AT10000003", "AT10000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT10000003"))).thenReturn(List.of(offer));
        when(offers.findOpen(anyString(), eq("AT10000003"), any(), anyBoolean()))
                .thenReturn(Optional.of(offer));

        assertThat(service.variantMoved(UUID.randomUUID(),
                new VariantMoved("AT10000003", SURVIVOR, "AT20000001", CATEGORY, true))).isEqualTo(1);
        verify(offers, never()).update(any());
    }

    @Test
    void aMoveEventDeliveredTwiceMovesNothingTheSecondTime() {
        UUID eventId = UUID.randomUUID();
        when(inbox.firstDelivery(OfferRelocationServiceImpl.MOVE_CONSUMER, eventId)).thenReturn(true, false);
        when(offers.findOpenByAtins(List.of("AT10000003")))
                .thenReturn(List.of(offer("org_b", "SKU-B", "AT10000003", "AT10000001", OfferStatus.ACTIVE)));
        VariantMoved moved = new VariantMoved("AT10000003", SURVIVOR, "AT20000001", CATEGORY, true);

        assertThat(service.variantMoved(eventId, moved)).isEqualTo(1);
        assertThat(service.variantMoved(eventId, moved)).isZero();
    }

    @Test
    void anOfferChangedMeanwhileDoesNotStopTheOthersFromFollowing() {
        Offer first = offer("org_b", "SKU-B", "AT10000003", "AT10000001", OfferStatus.ACTIVE);
        Offer second = offer("org_c", "SKU-C", "AT10000003", "AT10000001", OfferStatus.ACTIVE);
        when(offers.findOpenByAtins(List.of("AT10000003"))).thenReturn(List.of(first, second));
        when(offers.relocate(any())).thenThrow(new ConflictException("offers.concurrent-update", "changed"))
                .thenAnswer(i -> i.getArgument(0));

        assertThat(service.variantMoved(UUID.randomUUID(),
                new VariantMoved("AT10000003", SURVIVOR, "AT20000001", CATEGORY, true))).isEqualTo(1);
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static Merged merged(Map<String, String> variantMap) {
        return new Merged("AT20000001", SURVIVOR, "AT10000001", CATEGORY, variantMap);
    }

    private static Offer offer(String org, String sku, String atin, String productAtin, OfferStatus status) {
        return new Offer(UUID.randomUUID(), atin, productAtin, UUID.randomUUID(), null, UUID.randomUUID(), org,
                SellerKind.SELLER, sku, sku, OfferCondition.NEW, 999, null, "USD", 0, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), status, true, NOW, NOW, 0);
    }
}
