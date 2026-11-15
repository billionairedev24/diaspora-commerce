package com.afrotransact.payments.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.MembershipBenefitsService;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.application.SplitService;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.StripeGatewayException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S6 for payments' published APIs: one case per API method. The services are stand-ins that require the permission the real
 * ones do, so a caller without {@code payments:service} must leave as 403 exactly like a token without the scope.
 */
class PaymentsApiAdaptersTest {

    private static final String PERMISSION = "payments:service";

    private static final OrderPaymentsApi ORDER_PAYMENTS = new OrderPaymentsApiAdapter(
            InProcessKit.guarded(PaymentService.class, PERMISSION), InProcessKit.guarded(FeeScheduleService.class, PERMISSION),
            InProcessKit.guarded(SettlementService.class, PERMISSION), InProcessKit.guarded(SplitService.class, PERMISSION), InProcessKit.ACTORS);
    private static final MembershipBenefitsApi MEMBERSHIPS = new MembershipBenefitsApiAdapter(
            InProcessKit.guarded(MembershipBenefitsService.class, PERMISSION), InProcessKit.ACTORS);

    private static Arguments method(String name, Runnable call) {
        return Arguments.of(name, call);
    }

    static Stream<Arguments> everyMethod() {
        OrderPaymentsApi.NewPayment payment = new OrderPaymentsApi.NewPayment("o1", 1000, "USD", Instant.now(), Map.of(),
                List.of(new OrderPaymentsApi.Line("l1", "org", "food", "SELF", 1, 1000, 0, 0, null, 0, 0)), null, List.of(),
                false, false, false, List.of());
        return Stream.of(
                method("create", () -> ORDER_PAYMENTS.create(payment, "o1")),
                method("get", () -> ORDER_PAYMENTS.get("6f1c1a3e-0000-4000-8000-000000000001", false)),
                method("findByOrder", () -> ORDER_PAYMENTS.findByOrder("o1")),
                method("cancel", () -> ORDER_PAYMENTS.cancel("6f1c1a3e-0000-4000-8000-000000000001", "why", "k")),
                method("capture", () -> ORDER_PAYMENTS.capture("6f1c1a3e-0000-4000-8000-000000000001",
                        new OrderPaymentsApi.CaptureRequest("s1", List.of("l1"), 1000, false), "k")),
                method("refund", () -> ORDER_PAYMENTS.refund("6f1c1a3e-0000-4000-8000-000000000001", "k",
                        List.of(new OrderPaymentsApi.RefundLine("l1", 1, 0, 0)), "why")),
                method("delivered", () -> ORDER_PAYMENTS.delivered("o1", Instant.now())),
                method("shipmentDelivered", () -> ORDER_PAYMENTS.shipmentDelivered("o1", "s1", Instant.now())),
                method("split", () -> ORDER_PAYMENTS.split("o1")),
                method("quoteDelivery", () -> ORDER_PAYMENTS.quoteDelivery("org", 3.5, 1)),
                method("earlyAccessHours", () -> MEMBERSHIPS.earlyAccessHours("c1")),
                method("quoteWaivers", () -> MEMBERSHIPS.quote("c1", 1000, "USD",
                        List.of(new MembershipBenefitsApi.Fee("s1", "CARRIER", 500)))));
    }

    @ParameterizedTest(name = "{0} without payments:service is 403")
    @MethodSource("everyMethod")
    void everyMethodRefusesACallerWithoutTheScope(String method, Runnable call) {
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), call);
    }

    @Test
    void domainRefusalsKeepTheStatusTheEndpointAnswers() {
        assertThat(PaymentsFailures.map(new PaymentsStateConflict("payments.already-captured", "captured")))
                .get().extracting(ModuleApiException::status, ModuleApiException::code)
                .containsExactly(409, "payments.already-captured");
        assertThat(PaymentsFailures.map(new StripeGatewayException("declined", "card_declined", "generic_decline", 402,
                null))).get().extracting(ModuleApiException::status).isEqualTo(402);
        assertThat(PaymentsFailures.map(new IllegalStateException("x"))).isEqualTo(Optional.empty());
        assertThat(Set.of(1)).hasSize(1);
    }
}
