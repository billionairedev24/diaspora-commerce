package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.api.OrderPaymentsApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Orders talks to payments through its published APIs as its own principal and fails like the HTTP client does. */
class InProcessPaymentsClientTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service",
            Set.of("payments:service"));

    private final OrderPaymentsApi payments = Mockito.mock(OrderPaymentsApi.class);
    private final MembershipBenefitsApi memberships = Mockito.mock(MembershipBenefitsApi.class);
    private final PaymentsClient client;
    private final MemberBenefits benefits;

    InProcessPaymentsClientTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("payments", payments);
        beans.registerSingleton("memberships", memberships);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        ModuleUpstream upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
        client = new InProcessPaymentsClient(beans.getBeanProvider(OrderPaymentsApi.class), upstream, beans);
        benefits = new InProcessMemberBenefits(beans.getBeanProvider(MembershipBenefitsApi.class), upstream, beans);
    }

    @Test
    void createsTheIntentWithTheOrderIdAsKeyAndTheCapturePlan() {
        Mockito.when(payments.create(Mockito.any(), Mockito.eq("o1"))).thenAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("orders-service");
            OrderPaymentsApi.NewPayment request = call.getArgument(0);
            assertThat(request.metadata()).containsEntry("capture_plan", "per_shipment");
            assertThat(request.shopperId()).isNull();
            return new OrderPaymentsApi.PaymentInfo("p1", "REQUIRES_PAYMENT_METHOD", "secret", 1000, null, 0);
        });
        PaymentsClient.Intent intent = client.createIntent(new PaymentsClient.NewIntent("o1", 1000, "USD", Instant.now(),
                Map.of(), List.of(new PaymentsClient.Line("l1", "org", "food", "SELF", 1, 1000, 0, 0, null)), " "));
        assertThat(intent.paymentId()).isEqualTo("p1");
        assertThat(intent.clientSecret()).isEqualTo("secret");
    }

    @Test
    void aDeclineIsA402NotRetriedAndAMissingPaymentIsEmpty() {
        Mockito.when(payments.get("p1", true)).thenThrow(new ModuleApiException(402, "payments.card-declined", "declined"));
        assertThatThrownBy(() -> client.get("p1", true)).isInstanceOfSatisfying(UpstreamFailure.class, f -> {
            assertThat(f.status()).isEqualTo(402);
            assertThat(f.retryable()).isFalse();
            assertThat(f.code()).isEqualTo("payments.card-declined");
        });
        Mockito.when(payments.findByOrder("o9")).thenReturn(Optional.empty());
        assertThat(client.findByOrder("o9")).isEmpty();
    }

    @Test
    void keysFollowTheHttpClientAndAStripeOutageIsRetryable() {
        client.cancel("p1", "why");
        Mockito.verify(payments).cancel("p1", "why", "cancel-p1");
        Mockito.when(payments.capture(Mockito.eq("p1"), Mockito.any(), Mockito.eq("capture-s1")))
                .thenThrow(new ModuleApiException(502, "payments.stripe-unavailable", "down"));
        assertThatThrownBy(() -> client.capture("p1", new PaymentsClient.CaptureRequest("s1", List.of("l1"), 1000, false)))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    void membershipAnswersAreAdvisory() {
        Mockito.when(memberships.earlyAccessHours("c1")).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        assertThat(benefits.earlyAccessHours("c1")).isZero();
        Mockito.when(memberships.quote(Mockito.eq("c1"), Mockito.eq(1000L), Mockito.eq("USD"), Mockito.any()))
                .thenReturn(new MembershipBenefitsApi.WaiverQuoteInfo(true, "PLUS", null,
                        List.of(new MembershipBenefitsApi.Waiver("s1", "FREE_DELIVERY", 500))));
        Optional<MemberBenefits.Waivers> waivers = benefits.waivers("c1", 1000, "USD",
                List.of(new MemberBenefits.Fee("s1", MemberBenefits.FeeKind.CARRIER, 500)));
        assertThat(waivers).get().extracting(MemberBenefits.Waivers::tierName).isEqualTo("PLUS");
    }
}
