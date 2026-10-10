package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class SplitServiceImpl implements SplitService {

    private final PaymentRepository payments;
    private final FeeScheduleRepository feeSchedules;
    private final PaymentSplitter splitter;
    private final PaymentsAccess access;

    SplitServiceImpl(PaymentRepository payments, FeeScheduleRepository feeSchedules, PaymentSplitter splitter,
                     PaymentsAccess access) {
        this.payments = payments;
        this.feeSchedules = feeSchedules;
        this.splitter = splitter;
        this.access = access;
    }

    /** Captured lines use Stripe's fees; the uncaptured remainder uses the schedule's card-fee estimate. */
    @Override
    public OrderSplit splitOf(String orderId) {
        access.reader();
        Payment p = payments.findByOrderId(orderId).orElseThrow(() -> new NotFoundException(
                "payments.payment-not-found", "No payment for order " + orderId));
        FeeSchedule schedule = feeSchedules.findById(p.feeScheduleId()).orElseThrow();
        List<Capture> captures = payments.captures(p.id());
        Money fee = captures.stream().map(Capture::stripeFee).reduce(Money.zero(p.currency()), Money::plus);
        Money uncaptured = p.lines().stream().filter(l -> !l.isCaptured()).map(l -> l.line().total())
                .reduce(Money.zero(p.currency()), Money::plus);
        fee = fee.plus(schedule.estimateCardFee(uncaptured));
        boolean anyEstimate = uncaptured.isPositive() || captures.stream().anyMatch(Capture::feeEstimated);
        boolean anyActual = captures.stream().anyMatch(c -> !c.feeEstimated());
        String source = anyEstimate && anyActual ? "MIXED" : anyActual ? "ACTUAL" : "ESTIMATED";
        List<OrderLine> lines = p.lines().stream().map(PaymentLine::line).toList();
        return new OrderSplit(orderId, p.id(), schedule.version(), source, splitter.split(lines, schedule, fee));
    }
}
