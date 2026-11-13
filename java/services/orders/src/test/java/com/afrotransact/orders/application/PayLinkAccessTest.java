package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderOrigin;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PayLinkSigner;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay-now links (TOKEN_LINK): only gateway-bff resolves them, only with the order's own unexpired token. */
class PayLinkAccessTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    private final TenantFixture tenants = new TenantFixture();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PayLinkSigner signer = mock(PayLinkSigner.class);
    private final PaymentsClient payments = mock(PaymentsClient.class);
    private final OrderPlacementService placement = mock(OrderPlacementService.class);
    private PayLinkServiceImpl service;
    private Order lateAdd;

    @BeforeEach
    void setUp() {
        OrdersAccess access = new OrdersAccess(tenants.permissions(), tenants.access(), tenants.resolver());
        service = new PayLinkServiceImpl(access, orders, mock(CheckoutRequestRepository.class), payments, placement,
                signer, mock(PreorderSettings.class), CLOCK);
        OrderOrigin origin = mock(OrderOrigin.class);
        when(origin.lateAdd()).thenReturn(true);
        when(origin.paymentLinkUrl()).thenReturn("https://pay/x");
        lateAdd = mock(Order.class);
        when(lateAdd.id()).thenReturn("order-1");
        when(lateAdd.number()).thenReturn("AT-2026-000001");
        when(lateAdd.origin()).thenReturn(origin);
        when(lateAdd.status()).thenReturn(OrderStatus.PENDING_PAYMENT);
        when(orders.findByNumber("AT-2026-000001")).thenReturn(Optional.of(lateAdd));
    }

    @Test
    void onlyGatewayBffServicesMayResolveOrConfirmAPayLink() {
        when(signer.verify(any(), any(), any())).thenReturn(Optional.of(CLOCK.instant().plus(Duration.ofHours(1))));
        List<Actor> others = List.of(
                TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read", "orders:pay-link"),
                TenantFixture.customer(TenantFixture.CUSTOMER_1, "orders:pay-link"),
                TenantFixture.staff("orders:read", "orders:pay-link"),
                TenantFixture.service("orders:read"));
        for (Actor other : others) {
            tenants.as(other);
            CrossTenant.assertDenied(() -> service.lookup("AT-2026-000001", "token"));
            CrossTenant.assertDenied(() -> service.confirm("AT-2026-000001", "token"));
        }
        verify(payments, never()).get(any(), any(Boolean.class));
        verify(placement, never()).place(any());
    }

    @Test
    void aWrongTokenAndAnUnknownOrderAreTheSame404() {
        tenants.as(TenantFixture.service("orders:pay-link"));
        when(signer.verify("order-1", "AT-2026-000001", "wrong")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup("AT-2026-000001", "wrong")).isInstanceOf(NotFoundException.class)
                .hasMessage(catching(() -> service.lookup("AT-2026-999999", "wrong")));
        assertThatThrownBy(() -> service.confirm("AT-2026-000001", "wrong")).isInstanceOf(NotFoundException.class);
        verify(placement, never()).place(any());
    }

    @Test
    void anExpiredTokenOfAnUnpaidOrderIsRefusedAndNothingIsPlaced() {
        tenants.as(TenantFixture.service("orders:pay-link"));
        when(signer.verify("order-1", "AT-2026-000001", "old"))
                .thenReturn(Optional.of(CLOCK.instant().minusSeconds(1)));

        assertThatThrownBy(() -> service.confirm("AT-2026-000001", "old")).hasMessageContaining("expired");
        verify(placement, never()).place(any());
        assertThat(lateAdd.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    private static String catching(Runnable call) {
        try {
            call.run();
        } catch (NotFoundException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected a 404");
    }
}
