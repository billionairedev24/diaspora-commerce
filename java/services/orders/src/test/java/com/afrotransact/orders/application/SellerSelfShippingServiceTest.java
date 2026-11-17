package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.SellerSelfShipping;
import com.afrotransact.orders.domain.ShippingSettingsFixtures;
import com.afrotransact.orders.port.SellerSelfShippingRepository;
import com.afrotransact.orders.port.ShippingSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The seller's own flat shipping charge: nothing can be set or offered while the staff switch is off (the default). */
class SellerSelfShippingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private OrdersAccess access;
    private ShippingSettingsRepository settings;
    private SellerSelfShippingRepository sellers;
    private SellerSelfShippingService service;

    @BeforeEach
    void setUp() {
        access = mock(OrdersAccess.class);
        settings = mock(ShippingSettingsRepository.class);
        sellers = mock(SellerSelfShippingRepository.class);
        when(access.sellerOrgToManage(null)).thenReturn("org-seller");
        when(access.current()).thenReturn(new Actor("user-1", PrincipalType.SELLER_MEMBER, Optional.of("org-seller"), Set.of(),
                Set.of()));
        when(settings.find("USD")).thenReturn(Optional.of(ShippingSettingsFixtures.of(599, null, false)));
        service = new SellerSelfShippingServiceImpl(access, settings, sellers, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void switchOn() {
        when(settings.find("USD")).thenReturn(Optional.of(ShippingSettingsFixtures.withSelfShipping(599, null, true)));
    }

    @Test
    void switchOffRefusesToSetAChargeAndSavesNothing() {
        assertThatThrownBy(() -> service.replace(null, "USD", true, 500))
                .isInstanceOf(OrdersStateConflict.class).hasMessageContaining("cannot ship orders themselves yet");
        verify(sellers, never()).save(any());
    }

    @Test
    void switchOffSaysNotAvailableAndOffersNoSellerEvenWithAStoredCharge() {
        when(sellers.findAll(List.of("org-seller"))).thenReturn(List.of(
                new SellerSelfShipping("org-seller", "USD", 500, true, 1, NOW, "user-1")));
        assertThat(service.mine(null, "USD").available()).isFalse();
        assertThat(service.offered(List.of("org-seller"), "USD")).isEmpty();
    }

    @Test
    void switchOnSavesOneFlatChargeForTheSellerAndOffersIt() {
        switchOn();
        SellerSelfShippingService.Setting saved = service.replace(null, "USD", true, 750);
        assertThat(saved.available()).isTrue();
        assertThat(saved.chargeMinor()).isEqualTo(750);
        ArgumentCaptor<SellerSelfShipping> row = ArgumentCaptor.forClass(SellerSelfShipping.class);
        verify(sellers).save(row.capture());
        assertThat(row.getValue().sellerOrgId()).isEqualTo("org-seller");
        assertThat(row.getValue().version()).isEqualTo(1);

        when(sellers.findAll(List.of("org-seller"))).thenReturn(List.of(row.getValue()));
        assertThat(service.offered(List.of("org-seller"), "USD")).singleElement()
                .satisfies(s -> assertThat(s.chargeMinor()).isEqualTo(750));
    }

    @Test
    void aSellerWhoOptedOutIsNotOfferedAndTheChargeHasACeiling() {
        switchOn();
        when(sellers.findAll(List.of("org-seller"))).thenReturn(List.of(
                new SellerSelfShipping("org-seller", "USD", 500, false, 2, NOW, "user-1")));
        assertThat(service.offered(List.of("org-seller"), "USD")).isEmpty();
        assertThatThrownBy(() -> service.replace(null, "USD", true, 10_001)).isInstanceOf(OrdersRuleViolation.class)
                .hasMessageContaining("between 0 and 100.00");
        assertThat(service.replace(null, "USD", true, 0).chargeMinor()).as("0 is free shipping").isZero();
    }
}
