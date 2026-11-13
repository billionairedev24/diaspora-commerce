package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * /v1/me/run-subscriptions (CUSTOMER_SELF, keyed by the token subject only) and the public token links
 * (confirm, unsubscribe): an unknown or foreign token reveals and changes nothing.
 */
class SubscriptionTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final UUID LOCATION = UUID.randomUUID();

    private final TenantFixture tenants = new TenantFixture();
    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final LocationRepository locations = mock(LocationRepository.class);
    private final SecretTokens tokens = new SecretTokens();
    private SubscriptionServiceImpl service;

    private final Subscription second = Subscription.forUser(UUID.randomUUID(), LOCATION, TenantFixture.CUSTOMER_2,
            Set.of(Channel.EMAIL), "test", "unsub-2", NOW);

    @BeforeEach
    void setUp() {
        Location location = mock(Location.class);
        when(location.active()).thenReturn(true);
        when(locations.findById(LOCATION)).thenReturn(Optional.of(location));
        when(subscriptions.findByUser(LOCATION, TenantFixture.CUSTOMER_2)).thenReturn(Optional.of(second));
        when(subscriptions.findByUser(LOCATION, TenantFixture.CUSTOMER_1)).thenReturn(Optional.empty());
        when(subscriptions.findAllByUser(TenantFixture.CUSTOMER_2)).thenReturn(List.of(second));
        when(subscriptions.findAllByUser(TenantFixture.CUSTOMER_1)).thenReturn(List.of());
        when(subscriptions.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(subscriptions.update(any())).thenAnswer(i -> i.getArgument(0));
        service = new SubscriptionServiceImpl(subscriptions, locations, mock(AttributionService.class),
                mock(DeliveryRunsEventPublisher.class), tokens, mock(RunLinks.class), mock(DeliveryRunsSettings.class),
                tenants.resolver(), mock(PermissionChecker.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void customerOneNeverSeesCustomerTwosSubscriptions() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));

        assertThat(service.mine()).isEmpty();
        verify(subscriptions, never()).findAllByUser(TenantFixture.CUSTOMER_2);

        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));
        assertThat(service.mine()).containsExactly(second);
    }

    @Test
    void customerOneCannotWithdrawCustomerTwosSubscription() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));

        CrossTenant.assertDenied(() -> service.unsubscribeMine(LOCATION, null));

        verify(subscriptions, never()).update(any());
    }

    @Test
    void customerOneSubscribingCreatesItsOwnRowAndLeavesCustomerTwosAlone() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));

        Subscription created = service.subscribeMine(LOCATION, Set.of(Channel.EMAIL), "test");

        assertThat(created.userId()).isEqualTo(TenantFixture.CUSTOMER_1);
        verify(subscriptions, never()).update(any());
        verify(subscriptions, never()).findByUser(LOCATION, TenantFixture.CUSTOMER_2);
    }

    @Test
    void confirmAndUnsubscribeLinksNeedTheRightToken() {
        CrossTenant.assertDenied(() -> service.confirm("not-a-token"));
        CrossTenant.assertDenied(() -> service.confirm(" "));
        CrossTenant.assertDenied(() -> service.confirm(null));
        CrossTenant.assertDenied(() -> service.unsubscribe("not-a-token", null));
        CrossTenant.assertDenied(() -> service.unsubscribe("", null));
        CrossTenant.assertDenied(() -> service.unsubscribe(null, null));
        verify(subscriptions, never()).update(any());
    }

    @Test
    void confirmLookupUsesTheTokenHashNeverTheRawToken() {
        when(subscriptions.findByConfirmTokenHash(anyString())).thenReturn(Optional.empty());

        CrossTenant.assertDenied(() -> service.confirm("secret-token"));

        verify(subscriptions).findByConfirmTokenHash(eq(tokens.hash("secret-token")));
        verify(subscriptions, never()).findByConfirmTokenHash("secret-token");
    }
}
