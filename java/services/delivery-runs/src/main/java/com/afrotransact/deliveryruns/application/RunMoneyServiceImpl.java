package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunMoneyServiceImpl implements RunMoneyService {

    private static final Logger LOG = LoggerFactory.getLogger(RunMoneyServiceImpl.class);
    private static final String CAPTURES = "delivery-runs-run-money-captures";
    private static final String REFUNDS = "delivery-runs-run-money-refunds";

    private final RunOrderRepository runOrders;
    private final InboxDeduplicator inbox;
    private final Clock clock;

    RunMoneyServiceImpl(RunOrderRepository runOrders, InboxDeduplicator inbox, Clock clock) {
        this.runOrders = runOrders;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onCaptured(UUID captureId, String orderId, long commissionMinor, Instant at) {
        Optional<RunOrder> slot = runOrders.findCurrentByOrderId(orderId);
        if (slot.isEmpty() || commissionMinor < 0 || !inbox.firstDelivery(CAPTURES, captureId)) {
            return;
        }
        RunOrder o = slot.get();
        runOrders.update(o.withMoney(o.money().withCommission(commissionMinor), Instant.now(clock)));
        LOG.debug("Order {} on run {}: platform commission +{}", orderId, o.runId(), commissionMinor);
    }

    @Override
    @Transactional
    public void onRefunded(UUID refundId, String orderId, long amountMinor, long shippingMinor, Instant at) {
        Optional<RunOrder> slot = runOrders.findCurrentByOrderId(orderId);
        if (slot.isEmpty() || amountMinor <= 0 || !inbox.firstDelivery(REFUNDS, refundId)) {
            return;
        }
        RunOrder o = slot.get();
        Instant now = Instant.now(clock);
        long fee = Math.max(0, Math.min(shippingMinor, amountMinor));
        runOrders.update(o.withMoney(o.money().withRefund(amountMinor, fee, at == null ? now : at), now));
        LOG.debug("Order {} on run {}: refunded {} ({} delivery fee)", orderId, o.runId(), amountMinor, fee);
    }
}
