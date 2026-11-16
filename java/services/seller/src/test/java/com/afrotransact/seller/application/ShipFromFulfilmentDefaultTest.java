package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.PickupWindow;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.DeliveryZones;
import com.afrotransact.seller.port.Geocoder;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Owner decision: new locations default to Fulfilled by AfroTransact where that is possible (approved organisation,
 * street-level located address inside the delivery area, pickup hours given). Enforced here, server side, not by a tick
 * in the UI. Hours are never invented: no hours, nothing is enabled.
 */
class ShipFromFulfilmentDefaultTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final List<PickupWindow> HOURS = List.of(
            new PickupWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)));

    private final ShipFromAddressRepository addresses = mock(ShipFromAddressRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final Geocoder geocoder = mock(Geocoder.class);
    private final DeliveryZones zones = mock(DeliveryZones.class);
    private final List<ShipFromAddress> stored = new ArrayList<>();
    private ShipFromAddressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipFromAddressServiceImpl(addresses, orgs, mock(AccessGuard.class), geocoder, zones,
                mock(DomainEventPublisher.class), new SellerSettings(Set.of("US"), List.of(), "org_retail"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(addresses.list("org_a")).thenAnswer(i -> List.copyOf(stored));
        when(addresses.find(eq("org_a"), any())).thenAnswer(i -> stored.stream()
                .filter(a -> a.id().equals(i.getArgument(1))).findFirst());
        doAnswer(i -> stored.add(i.getArgument(0))).when(addresses).insert(any());
        when(addresses.update(any())).thenAnswer(i -> {
            ShipFromAddress next = i.getArgument(0);
            stored.removeIf(a -> a.id().equals(next.id()));
            stored.add(next);
            return true;
        });
        when(zones.nearest(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new DeliveryZones.ZoneMatch("Houston", true, 0)));
    }

    private void org(OrgStatus status) {
        when(orgs.findById("org_a")).thenReturn(Optional.of(new Organisation("org_a", OrgKind.SELLER, "Mama Put LLC",
                "Mama Put", "US", null, status, NOW, NOW, 0)));
    }

    private static ShipFromAddressInput input(List<PickupWindow> hours) {
        return new ShipFromAddressInput("Kitchen", "Ada Obi", null, null, "4521 Main St", null, "Houston", "TX",
                "77002", "US", false, null, hours);
    }

    private void located(String precision) {
        when(geocoder.geocode(any())).thenReturn(Optional.of(new GeoPosition(29.76, -95.37, precision, NOW)));
    }

    @Test
    void aNewLocationWithPickupHoursIsFulfilledByAfroTransactWhenPossible() {
        org(OrgStatus.APPROVED);
        located("ADDRESS");

        ShipFromAddress saved = service.create("org_a", input(HOURS));

        assertThat(saved.pickup().enabled()).isTrue();
        assertThat(saved.pickup().hours()).isEqualTo(HOURS);
        assertThat(saved.pickup().zoneName()).isEqualTo("Houston");
    }

    @Test
    void withoutPickupHoursNothingIsEnabledAndNoHoursAreInvented() {
        org(OrgStatus.APPROVED);
        located("ADDRESS");

        ShipFromAddress saved = service.create("org_a", input(List.of()));

        assertThat(saved.pickup().enabled()).isFalse();
        assertThat(saved.pickup().hours()).isEmpty();
    }

    @Test
    void notPossibleMeansSavedWithNothingEnabledNeverAnError() {
        org(OrgStatus.DRAFT);
        located("ADDRESS");
        assertThat(service.create("org_a", input(HOURS)).pickup().enabled()).as("not approved").isFalse();

        stored.clear();
        org(OrgStatus.APPROVED);
        located("ZIP");
        assertThat(service.create("org_a", input(HOURS)).pickup().enabled()).as("only the ZIP is located").isFalse();

        stored.clear();
        located("ADDRESS");
        when(zones.nearest(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        assertThat(service.create("org_a", input(HOURS)).pickup().enabled()).as("outside the area").isFalse();
        verify(addresses, never()).delete(any(), any());
    }

    @Test
    void theAddressIsAlwaysSavedWhatever() {
        org(OrgStatus.APPROVED);
        located("ZIP");
        service.create("org_a", input(HOURS));
        ArgumentCaptor<ShipFromAddress> inserted = ArgumentCaptor.forClass(ShipFromAddress.class);
        verify(addresses).insert(inserted.capture());
        assertThat(inserted.getValue().city()).isEqualTo("Houston");
    }
}
