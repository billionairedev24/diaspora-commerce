package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.UUID;

import com.afrotransact.offers.application.OfferTaxGate.Verdict;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.TaxDecisions;
import com.afrotransact.offers.port.TaxDecisions.DecisionState;
import com.afrotransact.offers.port.TaxDecisions.TaxUnavailable;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S384: the tax question of an offer, and the one component that releases offers held for tax. */
class TaxHoldTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String ATIN = "AT00000002";

    private final TaxDecisions decisions = mock(TaxDecisions.class);
    private final OfferTaxGateImpl gate = new OfferTaxGateImpl(decisions);

    private final OfferRepository offers = mock(OfferRepository.class);
    private final OfferTaxGate fakeGate = mock(OfferTaxGate.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final OfferSettingsService settings = mock(OfferSettingsService.class);
    private final OfferTaxMetrics metrics = mock(OfferTaxMetrics.class);
    private TaxHoldServiceImpl service;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(new OfferPolicy(200, 108, 150, 50, null, null, 0));
        when(offers.update(any())).thenAnswer(i -> i.getArgument(0));
        service = new TaxHoldServiceImpl(offers, fakeGate, buyBox, events, settings, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- the question

    @Test
    void aStoredResolvedDecisionIsEnoughAndNothingIsRequested() {
        when(decisions.find(ATIN, "org_b")).thenReturn(new DecisionState("RESOLVED", true, List.of()));

        assertThat(gate.check(ATIN, "org_b", Map.of())).isEqualTo(Verdict.ok());
        verify(decisions, never()).request(any(), any(), any());
    }

    @Test
    void withoutADecisionTaxReturnsIsAskedToDecideFromThePageFactsAndTheSellersBehaviour() {
        when(decisions.find(ATIN, "org_b")).thenReturn(new DecisionState("MISSING", false, List.of()));
        when(decisions.request(eq(ATIN), eq("org_b"), eq(Map.of("sold_heated", false))))
                .thenReturn(new DecisionState("RESOLVED", true, List.of()));

        assertThat(gate.check(ATIN, "org_b", Map.of("sold_heated", false)).resolved()).isTrue();
    }

    @Test
    void aDecisionThatCannotBeMadeHoldsTheOfferWithTheReasonAndNeverDefaultsARate() {
        when(decisions.find(ATIN, "org_b")).thenReturn(new DecisionState("MISSING", false, List.of()));
        when(decisions.request(any(), any(), any())).thenReturn(new DecisionState("MISSING_FACTS", false,
                List.of("Say whether it is sold heated")));
        assertThat(gate.check(ATIN, "org_b", Map.of())).isEqualTo(Verdict.held("TAX_MISSING_FACTS"));

        when(decisions.request(any(), any(), any())).thenReturn(new DecisionState("MISSING", false, List.of()));
        assertThat(gate.check(ATIN, "org_b", Map.of())).isEqualTo(Verdict.held("TAX_DECISION_MISSING"));
    }

    @Test
    void aStateThatIsNotResolvedHoldsTheOfferWithItsStatus() {
        when(decisions.find(ATIN, "org_b")).thenReturn(new DecisionState("NEEDS_APPROVAL", false, List.of()));
        when(decisions.request(any(), any(), any())).thenReturn(new DecisionState("NEEDS_APPROVAL", false, List.of()));

        assertThat(gate.check(ATIN, "org_b", Map.of())).isEqualTo(Verdict.held("TAX_NEEDS_APPROVAL"));
    }

    @Test
    void taxReturnsBeingUnreachableHoldsTheOffer() {
        when(decisions.find(any(), any())).thenThrow(new TaxUnavailable("down", null));

        assertThat(gate.check(ATIN, "org_b", Map.of())).isEqualTo(Verdict.held("TAX_UNAVAILABLE"));
    }

    // ---- the release

    @Test
    void aHeldOfferWhoseSellerNowHasADecisionIsReleasedOnceAndAnnounced() {
        Offer held = held("org_b");
        when(offers.findTaxHeld(eq(ATIN), anyInt())).thenReturn(List.of(held));
        when(fakeGate.check(eq(ATIN), eq("org_b"), any())).thenReturn(Verdict.ok());

        int released = service.releaseFor(ATIN);

        assertThat(released).isEqualTo(1);
        ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
        verify(offers).update(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(saved.getValue().taxBasis()).isEqualTo(Offer.TAX_BASIS_SELLER_DECISION);
        assertThat(saved.getValue().heldReason()).isNull();
        verify(events).publish(eq(OffersEvents.OFFER_TAX_RELEASED), eq(OffersEvents.AGGREGATE), eq(ATIN), any());
        verify(events).publish(eq(OffersEvents.OFFER_UPDATED), eq(OffersEvents.AGGREGATE), eq(ATIN), any());
        verify(buyBox).recompute(List.of(ATIN), "tax-release");
        verify(metrics).released();
    }

    @Test
    void anOfferWhoseSellerStillHasNoDecisionStaysHeldAndNothingIsPublished() {
        when(offers.findTaxHeld(eq(ATIN), anyInt())).thenReturn(List.of(held("org_b")));
        when(fakeGate.check(any(), any(), any())).thenReturn(Verdict.held("TAX_DECISION_MISSING"));

        assertThat(service.releaseFor(ATIN)).isZero();

        verify(offers, never()).update(any());
        verify(events, never()).publish(any(), any(), any(), any());
        verify(buyBox, never()).recompute(any(), any());
    }

    @Test
    void releasingTwiceIsHarmlessBecauseAReleasedOfferIsNoLongerHeld() {
        Offer held = held("org_b");
        when(offers.findTaxHeld(eq(ATIN), anyInt())).thenReturn(List.of(held)).thenReturn(List.of());
        when(fakeGate.check(any(), any(), any())).thenReturn(Verdict.ok());

        assertThat(service.releaseFor(ATIN)).isEqualTo(1);
        assertThat(service.releaseFor(ATIN)).isZero();

        verify(offers).update(any());
    }

    @Test
    void anOfferChangedWhileBeingReleasedIsLeftToTheNextRun() {
        when(offers.findTaxHeld(eq(ATIN), anyInt())).thenReturn(List.of(held("org_b")));
        when(fakeGate.check(any(), any(), any())).thenReturn(Verdict.ok());
        when(offers.update(any())).thenThrow(new ConflictException("offers.concurrent-update", "changed"));

        assertThat(service.releaseFor(ATIN)).isZero();
        verify(events, never()).publish(any(), any(), any(), any());
    }

    @Test
    void theSweepStampsLiveOffersThatHaveADecisionAndHoldsTheOnesThatHaveNone() {
        Offer withDecision = unverified("org_b", "SKU-1");
        Offer withoutDecision = unverified("org_c", "SKU-2");
        when(offers.findTaxHeld(eq(null), anyInt())).thenReturn(List.of());
        when(offers.findActiveWithoutTaxBasis(anyInt())).thenReturn(List.of(withDecision, withoutDecision));
        when(fakeGate.check(eq(ATIN), eq("org_b"), any())).thenReturn(Verdict.ok());
        when(fakeGate.check(eq(ATIN), eq("org_c"), any())).thenReturn(Verdict.held("TAX_DECISION_MISSING"));

        assertThat(service.sweep()).isEqualTo(2);

        ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
        verify(offers, times(2)).update(saved.capture());
        assertThat(saved.getAllValues().get(0).status()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(saved.getAllValues().get(0).taxBasis()).isEqualTo("SELLER_DECISION");
        assertThat(saved.getAllValues().get(1).status()).isEqualTo(OfferStatus.TAX_HELD);
        verify(events).publish(eq(OffersEvents.OFFER_TAX_HELD), eq(OffersEvents.AGGREGATE), eq(ATIN), any());
        verify(metrics).held("TAX_DECISION_MISSING");
    }

    @Test
    void theSweepDoesNotTakeLiveOffersOffSaleBecauseTaxReturnsIsDown() {
        when(offers.findTaxHeld(eq(null), anyInt())).thenReturn(List.of());
        when(offers.findActiveWithoutTaxBasis(anyInt())).thenReturn(List.of(unverified("org_b", "SKU-1")));
        when(fakeGate.check(any(), any(), any())).thenReturn(Verdict.held(Offer.HOLD_UNAVAILABLE));

        assertThat(service.sweep()).isZero();

        verify(offers, never()).update(any());
        verify(metrics, never()).held(any());
    }

    private static Offer held(String org) {
        return unverified(org, "SKU-H").heldForTax(Offer.HOLD_DECISION_MISSING, NOW);
    }

    private static Offer unverified(String org, String sku) {
        return new Offer(UUID.randomUUID(), ATIN, "AT00000001", null, null, null, org, SellerKind.SELLER, sku, sku,
                OfferCondition.NEW, 999, null, "USD", 0, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 0);
    }
}
