package com.afrotransact.payments.adapter.in.module;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.api.ReturnRefundApi;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.RefundCommand;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ReturnRefundApi}: the use cases behind {@code GET /v1/payments?orderId=} and
 * {@code POST /v1/payments/{id}/refunds}, entered under the calling module's principal. {@link PaymentService} applies the
 * permission checks exactly as for a token; the refund command is built the way the controller builds it (no fault, no card
 * credit, no chooser).
 */
@Component
class ReturnRefundApiAdapter implements ReturnRefundApi {

    private final PaymentService payments;
    private final ModuleEntry entry;

    ReturnRefundApiAdapter(PaymentService payments, ActorResolver actors) {
        this.payments = payments;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<ChargedPayment> paymentForOrder(UUID orderId) {
        return entry.run(() -> {
            try {
                Payment p = payments.getByOrder(orderId.toString());
                List<ChargedLine> lines = p.lines().stream().map(pl -> new ChargedLine(pl.line().lineId(),
                        pl.line().sellerOrg(), pl.line().quantity(), pl.line().items().minor(),
                        pl.line().shipping().minor(), pl.line().tax().minor(), pl.captureId() != null,
                        pl.refundedItems().minor(), pl.refundedShipping().minor(), pl.refundedTax().minor())).toList();
                return Optional.of(new ChargedPayment(p.id(), p.currency(), lines));
            } catch (NotFoundException notFound) {
                return Optional.<ChargedPayment>empty();
            }
        }, PaymentsFailures::map);
    }

    @Override
    public IssuedRefund refund(UUID paymentId, List<RefundAmount> lines, String reason, String idempotencyKey) {
        return entry.run(() -> {
            String currency = payments.get(paymentId, false).currency();
            var result = payments.refund(paymentId, new RefundCommand(lines.stream()
                    .map(l -> new RefundCommand.Line(l.lineId(), Money.of(l.itemsMinor(), currency),
                            Money.of(l.shippingMinor(), currency), Money.of(l.taxMinor(), currency))).toList(),
                    reason, null, false, null), idempotencyKey);
            return new IssuedRefund(String.valueOf(result.refund().id()), result.refund().status().name(),
                    result.refund().amount().minor());
        }, PaymentsFailures::map);
    }
}
