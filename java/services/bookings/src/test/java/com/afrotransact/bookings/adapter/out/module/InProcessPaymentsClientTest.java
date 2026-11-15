package com.afrotransact.bookings.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.api.BookingPaymentsApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Bookings reaches payments through its published API as its own principal and fails like the HTTP client does. */
class InProcessPaymentsClientTest {

    private static final CallerPrincipal BOOKINGS = new CallerPrincipal("bookings", "bookings-service",
            Set.of("payments:service"));

    private final BookingPaymentsApi payments = Mockito.mock(BookingPaymentsApi.class);
    private final PaymentsClient client;

    InProcessPaymentsClientTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("payments", payments);
        beans.registerSingleton("callers", (CallerPrincipals) module -> BOOKINGS);
        client = new InProcessPaymentsClient(beans.getBeanProvider(BookingPaymentsApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
    }

    @Test
    void createsTheIntentAsBookings() {
        UUID id = UUID.randomUUID();
        Mockito.when(payments.create(Mockito.any(), Mockito.eq("k1"))).thenAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("bookings-service");
            return new BookingPaymentsApi.BookingPayment(id, "b1", "REQUIRES_PAYMENT_METHOD", "secret", null, 0, null);
        });
        PaymentsClient.Intent intent = client.createIntent(new PaymentsClient.CreateIntent("b1", "org", 5000, "USD",
                List.of(new PaymentsClient.Line("l1", 5000)), Map.of(), null, "shopper", false, null), "k1");
        assertThat(intent.id()).isEqualTo(id);
        assertThat(intent.clientSecret()).isEqualTo("secret");
    }

    @Test
    void aDeclineKeepsItsStatusAndCodeAndAnUnexpectedFailureIsUnavailable() {
        UUID id = UUID.randomUUID();
        Mockito.when(payments.get(id, true)).thenThrow(new ModuleApiException(402, "payments.card-declined", "declined"));
        assertThatThrownBy(() -> client.get(id, true)).isInstanceOfSatisfying(PaymentsClientException.class, e -> {
            assertThat(e.status()).isEqualTo(402);
            assertThat(e.code()).isEqualTo("payments.card-declined");
            assertThat(e.rejected()).isTrue();
        });
        Mockito.when(payments.get(id, false)).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> client.get(id, false)).isInstanceOfSatisfying(PaymentsClientException.class,
                e -> assertThat(e.status()).isZero());
    }

    @Test
    void defaultReasonsMatchTheHttpClient() {
        UUID id = UUID.randomUUID();
        client.cancel(id, null, "k");
        Mockito.verify(payments).cancel(id, "Booking cancelled", "k");
        client.refund(id, "l1", 100, null, "k2");
        Mockito.verify(payments).refund(id, "l1", 100, "Booking cancelled", "k2");
    }
}
