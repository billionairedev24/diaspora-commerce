package com.afrotransact.payments.adapter.in.module;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.api.ClaimRefundApi;
import com.afrotransact.payments.application.OrderRefundCommand;
import com.afrotransact.payments.application.OrderRefundService;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.RefundCommand;
import com.afrotransact.payments.application.RefundResult;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ClaimRefundApi}: the use cases behind the two refund endpoints, entered under the calling
 * module's principal and built the way the controller builds them (no fault, no credit choice).
 */
@Component
class ClaimRefundApiAdapter implements ClaimRefundApi {

    private final PaymentService payments;
    private final OrderRefundService orderRefunds;
    private final ModuleEntry entry;

    ClaimRefundApiAdapter(PaymentService payments, OrderRefundService orderRefunds, ActorResolver actors) {
        this.payments = payments;
        this.orderRefunds = orderRefunds;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public ClaimRefund refund(UUID paymentId, List<RefundAmount> lines, String reason, String idempotencyKey) {
        return entry.run(() -> {
            String currency = payments.get(paymentId, false).currency();
            RefundResult result = payments.refund(paymentId, new RefundCommand(lines.stream()
                    .map(l -> new RefundCommand.Line(l.lineId(), Money.of(l.itemsMinor(), currency),
                            Money.of(l.shippingMinor(), currency), Money.of(l.taxMinor(), currency))).toList(), reason,
                    null, false, null), idempotencyKey);
            return view(result);
        }, PaymentsFailures::map);
    }

    @Override
    public ClaimRefund refundByOrder(String orderId, List<String> lineIds, long amountMinor, String reason,
                                     String idempotencyKey) {
        return entry.run(() -> view(orderRefunds.refundByOrder(new OrderRefundCommand(orderId, lineIds, amountMinor,
                reason, null, false, null), idempotencyKey)), PaymentsFailures::map);
    }

    private static ClaimRefund view(RefundResult r) {
        return new ClaimRefund(String.valueOf(r.refund().id()), r.refund().amount().minor(), r.refund().status().name(),
                r.replayed());
    }
}
