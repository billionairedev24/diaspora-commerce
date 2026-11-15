package com.afrotransact.trustops.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.api.ReviewModerationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.SellerStandingApi;
import com.afrotransact.payments.api.ClaimRefundApi;
import com.afrotransact.seller.api.SellerEnforcementApi;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.PaymentsClient;
import com.afrotransact.trustops.port.SellerEnforcementClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Trust-ops reaches payments and seller through their published APIs as its own principal and fails like the HTTP clients. */
class InProcessTrustOpsEdgesTest {

    private static final CallerPrincipal TRUST_OPS = new CallerPrincipal("trust-ops", "trust-ops-service",
            Set.of("payments:service", "sellers:enforce"));

    private final ClaimRefundApi refunds = Mockito.mock(ClaimRefundApi.class);
    private final SellerEnforcementApi enforcement = Mockito.mock(SellerEnforcementApi.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ModuleUpstream upstream;

    InProcessTrustOpsEdgesTest() {
        beans.registerSingleton("refunds", refunds);
        beans.registerSingleton("enforcement", enforcement);
        beans.registerSingleton("callers", (CallerPrincipals) module -> TRUST_OPS);
        upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
    }

    @Test
    void aClaimRefundGoesThroughPaymentsUnderTheClaimKeyAndReportsTheReplay() {
        UUID payment = UUID.randomUUID();
        Mockito.when(refunds.refundByOrder(Mockito.eq("o1"), Mockito.eq(List.of("l1")), Mockito.eq(500L),
                Mockito.eq("claim"), Mockito.eq("claim-1"))).thenAnswer(call -> {
                    assertThat(InProcessCalls.require().clientId()).isEqualTo("trust-ops-service");
                    return new ClaimRefundApi.ClaimRefund("r1", 500, "SUCCEEDED", true);
                });
        PaymentsClient client = new InProcessPaymentsClient(beans.getBeanProvider(ClaimRefundApi.class), upstream, beans);
        PaymentsClient.RefundResult result = client.refundByOrder("o1", List.of("l1"), 500, "claim", "claim-1");
        assertThat(result).isEqualTo(new PaymentsClient.RefundResult("r1", 500, "SUCCEEDED", true));
        Mockito.when(refunds.refund(Mockito.eq(payment), Mockito.any(), Mockito.eq("why"), Mockito.eq("k")))
                .thenThrow(new ModuleApiException(422, "payments.refund-exceeds-captured", "too much"));
        assertThatThrownBy(() -> client.refund(payment, List.of(new PaymentsClient.RefundLine("l1", 1, 0, 0)), "why", "k"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("payments.refund-exceeds-captured");
                });
    }

    @Test
    void suspendAndReinstateCutLongNotesAndKeepRefusals() {
        SellerEnforcementClient client = new InProcessSellerEnforcementClient(
                beans.getBeanProvider(SellerEnforcementApi.class), upstream, beans);
        client.suspend("org", SuspensionReason.values()[0], "n".repeat(2000));
        Mockito.verify(enforcement).suspend(Mockito.eq("org"), Mockito.eq(SuspensionReason.values()[0].name()),
                Mockito.argThat(note -> note.length() == 1000));
        Mockito.doThrow(new ModuleApiException(403, "auth.missing-permission", "no")).when(enforcement)
                .reinstate("org", "ok");
        assertThatThrownBy(() -> client.reinstate("org", "ok")).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void reviewModerationAndSellerStandingGoThroughTheirModules() {
        ReviewModerationApi reviews = Mockito.mock(ReviewModerationApi.class);
        SellerStandingApi standing = Mockito.mock(SellerStandingApi.class);
        beans.registerSingleton("reviews", reviews);
        beans.registerSingleton("standing", standing);
        UUID booking = UUID.randomUUID();
        new InProcessBookingsReviewClient(beans.getBeanProvider(ReviewModerationApi.class),
                upstream, beans).approve(booking, "n".repeat(1500));
        Mockito.verify(reviews).approve(Mockito.eq(booking), Mockito.argThat(note -> note.length() == 1000));
        Mockito.when(standing.rating("org")).thenReturn(Optional.of(4.5));
        new InProcessOffersClient(beans.getBeanProvider(SellerStandingApi.class), upstream, beans)
                .pushPerformance("org", 0.9, 0.01, false);
        Mockito.verify(standing).updatePerformance("org", 4.5, 0.9, 0.01, false);
    }
}
