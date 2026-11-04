package com.afrotransact.payments.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundAllocator;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class OrderRefundServiceImpl implements OrderRefundService {

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final RefundAllocator allocator;
    private final PaymentsAccess access;

    OrderRefundServiceImpl(PaymentRepository payments, PaymentService paymentService, RefundAllocator allocator,
                           PaymentsAccess access) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.allocator = allocator;
        this.access = access;
    }

    @Override
    public RefundResult refundByOrder(OrderRefundCommand cmd, String idempotencyKey) {
        access.service();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Refunds need an Idempotency-Key");
        }
        if (cmd.lineIds().isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "Name at least one line",
                    Map.of("lineIds", "Required"));
        }
        Payment payment = payments.findByOrderId(cmd.orderId()).orElseThrow(() -> new NotFoundException(
                "payments.payment-not-found", "No payment for order " + cmd.orderId()));
        Optional<Refund> earlier = payments.findRefund(Fingerprint.id("refund", payment.id().toString(),
                idempotencyKey));
        if (earlier.isPresent()) {
            return new RefundResult(earlier.get(), payment, List.of(), List.of(), true);
        }
        List<PaymentLine> lines = new ArrayList<>();
        for (String lineId : new LinkedHashSet<>(cmd.lineIds())) {
            lines.add(payment.line(lineId).orElseThrow(() -> new PaymentsRuleViolation("payments.unknown-line",
                    "Order " + cmd.orderId() + " has no line " + lineId, Map.of("lineIds", "Unknown " + lineId))));
        }
        Money amount = cmd.amountMinor() == null ? null : Money.of(cmd.amountMinor(), payment.currency());
        RefundCommand probe = new RefundCommand(List.of(), cmd.reason(), cmd.fault());
        List<RefundCommand.Line> refundLines = allocator.allocate(lines, amount, probe.faultOrDefault()).stream()
                .map(s -> new RefundCommand.Line(s.lineId(), s.items(), s.shipping(), s.tax())).toList();
        return paymentService.refund(payment.id(), new RefundCommand(refundLines, cmd.reason(), cmd.fault(), cmd.cardAsCredit(),
                cmd.chosenBy()),
                idempotencyKey);
    }
}
