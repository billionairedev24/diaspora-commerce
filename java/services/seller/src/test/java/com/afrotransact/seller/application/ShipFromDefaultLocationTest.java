package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.DeliveryZones;
import com.afrotransact.seller.port.Geocoder;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import org.junit.jupiter.api.Test;

/** The default ship-from lookup orders-service uses to tell tax-returns where a seller-fulfilled line ships from. */
class ShipFromDefaultLocationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final ShipFromAddressRepository addresses = mock(ShipFromAddressRepository.class);
    private final AccessGuard guard = mock(AccessGuard.class);
    private final ShipFromAddressServiceImpl service = new ShipFromAddressServiceImpl(addresses,
            mock(OrganisationRepository.class), guard, mock(Geocoder.class), mock(DeliveryZones.class),
            mock(DomainEventPublisher.class), mock(SellerSettings.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private static ShipFromAddress address(String line1, boolean isDefault) {
        return new ShipFromAddress(UUID.randomUUID(), "org_a", "Warehouse", "Ada", null, null, line1, null, "Dallas",
                "TX", "75201", "US", isDefault, NOW, NOW, 0);
    }

    private static Actor actor(PrincipalType type, String... permissions) {
        return new Actor("svc", type, Optional.empty(), Set.of(), Set.of(permissions));
    }

    @Test
    void aServiceWithTheScopeGetsTheDefaultAddress() {
        when(guard.current()).thenReturn(actor(PrincipalType.SERVICE, ShipFromAddressService.SERVICE_READ));
        when(addresses.list("org_a")).thenReturn(List.of(address("1 Side St", false), address("100 Commerce St", true)));

        assertThat(service.defaultLocation("org_a")).get().extracting(ShipFromAddress::line1)
                .isEqualTo("100 Commerce St");
    }

    @Test
    void anOrganisationWithoutAnAddressHasNoDefault() {
        when(guard.current()).thenReturn(actor(PrincipalType.SERVICE, ShipFromAddressService.SERVICE_READ));
        when(addresses.list("org_a")).thenReturn(List.of());

        assertThat(service.defaultLocation("org_a")).isEmpty();
    }

    @Test
    void anyoneWithoutTheScopeMustBeAMemberOrStaff() {
        when(guard.current()).thenReturn(actor(PrincipalType.CUSTOMER));
        when(guard.orgRole("org_a", ShipFromAddressServiceImpl.READERS, ShipFromAddressService.STAFF_PERMISSION))
                .thenThrow(new ForbiddenActionException("seller.org-role-required", "no"));

        assertThatThrownBy(() -> service.defaultLocation("org_a")).isInstanceOf(ForbiddenActionException.class);
    }
}
