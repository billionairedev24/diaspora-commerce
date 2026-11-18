package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.CatalogClient.CatalogVariant;
import com.afrotransact.offers.port.CatalogClient.Eligibility;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.offers.port.PickupLocationClient.PickupLocation;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The owner's case: Add a product, Pick up, an address selected. The form and the gateway send only the location's id,
 * so a Pick up offer is resolved from that location; a refusal names what is missing, on the location field.
 */
class PickUpOfferLocationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String ATIN = "AT00000002";
    private static final String LOCATION = "loc-houston";
    private static final OfferPolicy POLICY = new OfferPolicy(200, 108, 150, 200, null, null, 0);

    private final OfferRepository offers = mock(OfferRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final PickupLocationClient locations = mock(PickupLocationClient.class);
    private OfferServiceImpl service;

    @BeforeEach
    void setUp() {
        PermissionChecker permissions = mock(PermissionChecker.class);
        when(permissions.require(anyString())).thenReturn(new Actor("user_b", PrincipalType.SELLER_MEMBER,
                Optional.of("org_b"), Set.of(), Set.of(OffersPermissions.WRITE, OffersPermissions.READ)));
        when(catalog.liveVariant(ATIN)).thenReturn(Optional.of(new CatalogVariant(UUID.randomUUID(), "AT00000001", ATIN,
                null, UUID.randomUUID(), "Ground Egusi (Melon Seeds)", "org_a")));
        when(catalog.eligibility(eq(ATIN), anyString())).thenReturn(new Eligibility(true, List.of(), List.of("NEW")));
        OfferSettingsService policy = mock(OfferSettingsService.class);
        when(policy.current()).thenReturn(POLICY);
        OfferTaxGate taxGate = mock(OfferTaxGate.class);
        when(taxGate.check(anyString(), anyString(), any())).thenReturn(Verdict.ok());
        when(offers.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        ListingEntitlementsClient entitlements = org -> ListingEntitlementsClient.ListingAllowance.UNLIMITED;
        service = new OfferServiceImpl(offers, catalog, locations, entitlements, mock(BuyBoxService.class),
                mock(BuyBoxEvaluator.class), permissions, mock(ResourceAccessChecker.class),
                mock(DomainEventPublisher.class), new OffersSettings("org_retail", 2, 2, 4, 5), taxGate,
                mock(TaxHoldService.class), policy, mock(OfferTaxMetrics.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateOfferCommand pickUp(ShipFrom shipFrom) {
        return new CreateOfferCommand(ATIN, null, null, "EGUSI-1", null, OfferCondition.NEW, 3000, 4500L, "USD", 0,
                Fulfilment.SELLER, 300, 2, shipFrom, null, null, Map.of(), Set.of());
    }

    private static PickupLocation location(String orgId, String city) {
        return new PickupLocation(LOCATION, orgId, false, city, "TX", "US", "77002");
    }

    private Offer stored() {
        ArgumentCaptor<Offer> captor = ArgumentCaptor.forClass(Offer.class);
        verify(offers).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void aPickUpOfferWithOnlyTheSelectedLocationIdIsAcceptedAndTakesThePlaceFromTheLocation() {
        when(locations.find(LOCATION)).thenReturn(Optional.of(location("org_b", "Houston")));

        service.create(pickUp(new ShipFrom(null, null, null, null, LOCATION)));

        ShipFrom shipFrom = stored().shipFrom();
        assertThat(shipFrom.locationId()).isEqualTo(LOCATION);
        assertThat(shipFrom.city()).isEqualTo("Houston");
        assertThat(shipFrom.region()).isEqualTo("TX");
        assertThat(shipFrom.country()).isEqualTo("US");
        assertThat(shipFrom.postalCode()).isEqualTo("77002");
    }

    @Test
    void noChosenLocationSaysWhatIsMissingOnTheLocationFieldNeverShipFromWording() {
        assertThatThrownBy(() -> service.create(pickUp(null))).isInstanceOfSatisfying(OfferRuleViolation.class, e -> {
            assertThat(e.fieldErrors()).containsOnlyKeys("shipFrom.locationId");
            assertThat(e.fieldErrors().get("shipFrom.locationId")).isEqualTo("Choose where customers collect it");
            assertThat(e.fieldErrors().toString()).doesNotContain("Seller-fulfilled").doesNotContain("ship-from");
        });
    }

    @Test
    void anotherSellersLocationIsRefused() {
        when(locations.find(LOCATION)).thenReturn(Optional.of(location("org_other", "Houston")));

        assertThatThrownBy(() -> service.create(pickUp(new ShipFrom(null, null, null, null, LOCATION))))
                .isInstanceOfSatisfying(OfferRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("shipFrom.locationId", "That is not one of your locations"));
    }

    @Test
    void aLocationWithoutAnAddressIsRefusedAndTheAddressIsNamed() {
        when(locations.find(LOCATION)).thenReturn(Optional.of(location("org_b", " ")));

        assertThatThrownBy(() -> service.create(pickUp(new ShipFrom(null, null, null, null, LOCATION))))
                .isInstanceOfSatisfying(OfferRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("shipFrom.locationId", "Add the city to this location's address"));
    }

    @Test
    void aFullAddressFromAnotherWriterStillPasses() {
        service.create(pickUp(new ShipFrom("Houston", "TX", "US", "77002")));

        assertThat(stored().shipFrom().city()).isEqualTo("Houston");
    }
}
