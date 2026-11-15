package com.afrotransact.payments.adapter.in.module;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.api.ClaimRefundApi;
import com.afrotransact.payments.application.OrderRefundService;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** S6 for the claim refunds: {@code payments:service} is required, a caller without it is refused with 403. */
class ClaimRefundApiAdapterTest {

    private final ClaimRefundApi api = new ClaimRefundApiAdapter(InProcessKit.guarded(PaymentService.class, "payments:service"),
            InProcessKit.guarded(OrderRefundService.class, "payments:service"), InProcessKit.ACTORS);

    @Test
    void refundWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> api.refund(UUID.randomUUID(),
                List.of(new ClaimRefundApi.RefundAmount("l1", 1, 0, 0)), "why", "k"));
    }

    @Test
    void refundByOrderWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"),
                () -> api.refundByOrder("o1", List.of("l1"), 100, "why", "k"));
    }
}
