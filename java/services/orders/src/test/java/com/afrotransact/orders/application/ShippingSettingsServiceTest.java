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
import java.util.Optional;
import java.util.Set;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;
import com.afrotransact.orders.domain.ShippingSettingsFixtures;
import com.afrotransact.orders.port.ShippingSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Staff edits of the shipping settings: permission, a required reason, versions, no-op saves and the audit log. */
class ShippingSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String WHY = "Fuel costs went up in November";

    private ShippingSettingsRepository repo;
    private PermissionChecker permissions;
    private ShippingSettingsService service;

    @BeforeEach
    void setUp() {
        repo = mock(ShippingSettingsRepository.class);
        permissions = mock(PermissionChecker.class);
        Actor actor = new Actor("staff-9", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of(OrdersPermissions.SHIPPING_EDIT));
        when(permissions.require(OrdersPermissions.SHIPPING_EDIT)).thenReturn(actor);
        when(repo.lock("USD")).thenReturn(Optional.of(ShippingSettingsFixtures.of(599, null, false)));
        service = new ShippingSettingsServiceImpl(repo, permissions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aChangeNeedsAReasonAndMakesAVersionWithAnAuditEntry() {
        ShippingSettings saved = service.replace("USD", new ShippingSettingsCommand(699, 6000L, false, WHY), 1L);

        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.flatFeeMinor()).isEqualTo(699);
        assertThat(saved.updatedBy()).isEqualTo("staff-9");
        ArgumentCaptor<ShippingSettingsChange> log = ArgumentCaptor.forClass(ShippingSettingsChange.class);
        verify(repo).log(log.capture());
        assertThat(log.getValue().reason()).isEqualTo(WHY);
        assertThat(log.getValue().before().flatFeeMinor()).isEqualTo(599);
        assertThat(log.getValue().after().flatFeeMinor()).isEqualTo(699);
        assertThat(log.getValue().actorId()).isEqualTo("staff-9");
    }

    @Test
    void withoutAReasonNothingChanges() {
        assertThatThrownBy(() -> service.replace("USD", new ShippingSettingsCommand(699, null, false, "  short "), 1L))
                .isInstanceOf(OrdersRuleViolation.class).hasMessageContaining("Say why")
                .extracting(e -> ((OrdersRuleViolation) e).code()).isEqualTo("orders.reason-required");
        verify(repo, never()).update(any());
        verify(repo, never()).log(any());
    }

    @Test
    void aStaleEditorIsToldToReload() {
        assertThatThrownBy(() -> service.replace("USD", new ShippingSettingsCommand(699, null, false, WHY), 7L))
                .isInstanceOf(OrdersStateConflict.class).hasMessageContaining("Reload");
        verify(repo, never()).update(any());
    }

    @Test
    void aSaveThatChangesNothingMakesNoVersion() {
        ShippingSettings same = service.replace("USD", new ShippingSettingsCommand(599, null, false, WHY), 1L);
        assertThat(same.version()).isEqualTo(1);
        verify(repo, never()).update(any());
        verify(repo, never()).log(any());
    }

    @Test
    void turningTheSellerCarrierSwitchOnIsAnAuditedChangeToo() {
        ShippingSettings saved = service.replace("USD", new ShippingSettingsCommand(599, null, true, WHY), null);
        assertThat(saved.sellerCarrierShippingEnabled()).isTrue();
        verify(repo).update(saved);
    }

    @Test
    void checkoutNeverGuessesWhenNothingCanBeRead() {
        when(repo.find("USD")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.current("USD")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be priced");
    }
}
