package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.afrotransact.offers.application.OfferTaxGate.Verdict;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.PageContentLocked;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.CatalogClient.Blocker;
import com.afrotransact.offers.port.CatalogClient.CatalogUnavailableException;
import com.afrotransact.offers.port.CatalogClient.CatalogVariant;
import com.afrotransact.offers.port.CatalogClient.Eligibility;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S383 and S384 at offer creation: page content is locked, the selling approval gate and the brand check apply, the
 * condition note and the Fulfilled by AfroTransact package follow the rules, and an offer is live only with the seller's
 * own RESOLVED tax decision.
 */
class MatchedOfferGuardsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String ATIN = "AT00000002";
    private static final OfferPolicy POLICY = new OfferPolicy(200, 108, 150, 200, null, null, 0);

    private final OfferRepository offers = mock(OfferRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final BuyBoxEvaluator evaluator = mock(BuyBoxEvaluator.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final OfferTaxGate taxGate = mock(OfferTaxGate.class);
    private final TaxHoldService taxHold = mock(TaxHoldService.class);
    private final OfferSettingsService policy = mock(OfferSettingsService.class);
    private final OfferTaxMetrics taxMetrics = mock(OfferTaxMetrics.class);
    private OfferServiceImpl service;

    @BeforeEach
    void setUp() {
        when(permissions.require(anyString())).thenReturn(new Actor("user_b", PrincipalType.SELLER_MEMBER,
                Optional.of("org_b"), Set.of(), Set.of(OffersPermissions.WRITE, OffersPermissions.READ)));
        when(catalog.liveVariant(ATIN)).thenReturn(Optional.of(new CatalogVariant(UUID.randomUUID(), "AT00000001", ATIN,
                null, UUID.randomUUID(), "Ankara tote", "org_a")));
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(true, List.of(),
                List.of("NEW", "RENEWED", "USED_GOOD")));
        when(policy.current()).thenReturn(POLICY);
        when(taxGate.check(anyString(), anyString(), any())).thenReturn(Verdict.ok());
        when(offers.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        ListingEntitlementsClient entitlements = org -> ListingEntitlementsClient.ListingAllowance.UNLIMITED;
        service = new OfferServiceImpl(offers, catalog, mock(PickupLocationClient.class), entitlements, buyBox,
                evaluator, permissions, mock(ResourceAccessChecker.class), events,
                new OffersSettings("org_retail", 2, 2, 4, 5), taxGate, taxHold, policy, taxMetrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateOfferCommand command(OfferCondition condition, String note, OfferPackage box, Fulfilment fulfilment,
                                              Set<String> unknown) {
        return new CreateOfferCommand(ATIN, null, null, "ANK-1", null, condition, 1299, null, "USD", 0, fulfilment, 5,
                2, new ShipFrom("Houston", "TX", "US", "77002"), note, box, Map.of(), unknown);
    }

    private Offer stored() {
        ArgumentCaptor<Offer> captor = ArgumentCaptor.forClass(Offer.class);
        verify(offers).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void pageContentIsRefusedWithThePlainMessageAndNothingIsStored() {
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER,
                Set.of("title", "images", "brand", "attributes", "categoryId"))))
                .isInstanceOfSatisfying(PageContentLocked.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("That detail belongs to the product page. Suggest an edit instead.");
                    assertThat(e.contentFields()).containsExactlyInAnyOrder("attributes", "brand", "categoryId", "images",
                            "title");
                    assertThat(e.otherFields()).isEmpty();
                });
        verify(offers, never()).insert(any());
    }

    @Test
    void anUnknownFieldIsRefusedToo() {
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER,
                Set.of("sellerSkuu")))).isInstanceOfSatisfying(PageContentLocked.class, e -> {
                    assertThat(e.aboutPageContent()).isFalse();
                    assertThat(e.otherFields()).containsExactly("sellerSkuu");
                });
    }

    @Test
    void aSellerWithoutSellingApprovalIsRefusedWithTheApprovalCode() {
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(false, List.of(new Blocker(
                "offers.selling-approval-required", "You need approval to sell this brand. Apply under Listings.")),
                List.of("NEW")));

        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.code()).isEqualTo("offers.selling-approval-required");
                    assertThat(e.getMessage()).isEqualTo("You need approval to sell this brand. Apply under Listings.");
                });
        verify(offers, never()).insert(any());
        verify(taxGate, never()).check(any(), any(), any());
    }

    @Test
    void aRetiredOrRejectedBrandNeverMatchesForANewOffer() {
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(false, List.of(new Blocker(
                "catalog.brand-unavailable", "This brand is not available on AfroTransact, so you cannot sell this "
                        + "product.")), List.of("NEW")));

        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("offers.brand-unavailable"));
    }

    @Test
    void aDepartmentOutsideTheScopeIsForbiddenAndAnOfflinePageIsNotFound() {
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(false, List.of(new Blocker(
                "catalog.department-not-allowed", "Your store is not set up to sell in Fashion")), List.of("NEW")));
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.department-not-allowed"));

        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(false, List.of(new Blocker(
                "catalog.page-not-live", "This product page is not on sale yet")), List.of("NEW")));
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo("offers.product-not-found"));
    }

    @Test
    void sellerServiceBeingDownFailsTheOfferInsteadOfGuessing() {
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(false, List.of(new Blocker(
                "catalog.selling-unavailable", "We could not check whether your store may sell this")), List.of("NEW")));

        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void aUsedItemNeedsANoteAndACategoryThatAllowsUsedItems() {
        assertThatThrownBy(() -> service.create(command(OfferCondition.USED_GOOD, " ", null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("offers.condition-note-required"));

        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(true, List.of(), List.of("NEW")));
        assertThatThrownBy(() -> service.create(command(OfferCondition.USED_GOOD, "A scratch", null, Fulfilment.SELLER,
                Set.of()))).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("offers.condition-not-allowed"));
    }

    @Test
    void theConditionNoteIsLimitedByTheStaffSetting() {
        String tooLong = "x".repeat(201);
        assertThatThrownBy(() -> service.create(command(OfferCondition.USED_GOOD, tooLong, null, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(OfferRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("conditionNote", "At most 200 characters"));

        when(policy.current()).thenReturn(new OfferPolicy(300, 108, 150, 200, null, null, 1));
        service.create(command(OfferCondition.USED_GOOD, tooLong, null, Fulfilment.SELLER, Set.of()));

        assertThat(stored().conditionNote()).hasSize(201);
    }

    @Test
    void aNewItemKeepsNoNote() {
        service.create(command(OfferCondition.NEW, "Ignored", null, Fulfilment.SELLER, Set.of()));

        assertThat(stored().conditionNote()).isNull();
    }

    @Test
    void aPackageIsAcceptedOnlyForFulfilledByAfroTransact() {
        OfferPackage box = new OfferPackage(12, 8, 4, 2.5);

        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, box, Fulfilment.SELLER, Set.of())))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("offers.package-not-allowed"));
        verify(offers, never()).insert(any());

        service.create(command(OfferCondition.NEW, null, box, Fulfilment.AFROTRANSACT, Set.of()));

        assertThat(stored().packaging()).isEqualTo(box);
    }

    @Test
    void aPackageLargerThanTheStaffLimitsIsRefused() {
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, new OfferPackage(120, 8, 4, 2.5),
                Fulfilment.AFROTRANSACT, Set.of()))).isInstanceOfSatisfying(OfferRuleViolation.class,
                e -> assertThat(e.fieldErrors()).containsKey("packaging"));
        assertThatThrownBy(() -> service.create(command(OfferCondition.NEW, null, new OfferPackage(12, 8, 4, 151),
                Fulfilment.AFROTRANSACT, Set.of()))).isInstanceOfSatisfying(OfferRuleViolation.class,
                e -> assertThat(e.fieldErrors()).containsKey("packaging.weightLb"));
    }

    @Test
    void anOfferWithTheSellersOwnResolvedDecisionIsActive() {
        service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of()));

        Offer offer = stored();
        assertThat(offer.status()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(offer.taxBasis()).isEqualTo(Offer.TAX_BASIS_SELLER_DECISION);
        assertThat(offer.heldReason()).isNull();
        verify(taxGate).check(eq(ATIN), eq("org_b"), any());
        verify(events, never()).publish(eq(OffersEvents.OFFER_TAX_HELD), any(), any(), any());
    }

    @Test
    void anOfferWithoutADecisionIsSavedTaxHeldNotLiveAndAnnounced() {
        when(taxGate.check(anyString(), anyString(), any())).thenReturn(Verdict.held(Offer.HOLD_DECISION_MISSING));

        service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of()));

        Offer offer = stored();
        assertThat(offer.status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(offer.heldReason()).isEqualTo("TAX_DECISION_MISSING");
        assertThat(offer.taxBasis()).isNull();
        assertThat(offer.live()).as("not buyable, not in search, not in the buy box").isFalse();
        verify(events).publish(eq(OffersEvents.OFFER_TAX_HELD), eq(OffersEvents.AGGREGATE), eq(ATIN), any());
        verify(taxMetrics).held("TAX_DECISION_MISSING");
        verify(buyBox).recompute(List.of(ATIN), OffersEvents.OFFER_CREATED);
    }

    @Test
    void taxReturnsBeingDownHoldsTheOfferInsteadOfMakingItLive() {
        when(taxGate.check(anyString(), anyString(), any())).thenReturn(Verdict.held(Offer.HOLD_UNAVAILABLE));

        service.create(command(OfferCondition.NEW, null, null, Fulfilment.SELLER, Set.of()));

        assertThat(stored().status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(stored().heldReason()).isEqualTo("TAX_UNAVAILABLE");
    }
}
