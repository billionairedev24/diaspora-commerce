package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.StripeGatewayException;
import com.afrotransact.payments.port.TransferRecoveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class TransferRecoveryLedgerImpl implements TransferRecoveryLedger {

    private static final Logger LOG = LoggerFactory.getLogger(TransferRecoveryLedgerImpl.class);
    private static final String SYSTEM = "system:settlement";

    private final TransferRecoveryRepository recoveries;
    private final LedgerPoster ledger;
    private final PaymentsMetrics metrics;

    TransferRecoveryLedgerImpl(TransferRecoveryRepository recoveries, LedgerPoster ledger, PaymentsMetrics metrics) {
        this.recoveries = recoveries;
        this.ledger = ledger;
        this.metrics = metrics;
    }

    @Override
    public TransferRecovery open(UUID refundId, SettlementTransfer transfer, Money amount,
                                 StripeGatewayException refusal, Instant now, String by) {
        UUID id = Fingerprint.id("transfer-recovery", refundId.toString(), transfer.id().toString());
        UUID journal = Fingerprint.id("journal", "transfer-recovery-opened", id.toString());
        TransferRecovery recovery = TransferRecovery.open(id, refundId, transfer.orderId(), transfer.orgId(),
                transfer.id(), transfer.stripeTransferId(), amount, refusal.stripeCode(), refusal.getMessage(),
                journal, now);
        if (!recoveries.insert(recovery)) {
            return recoveries.find(id).orElseThrow();
        }
        ledger.post(Postings.transferRecoveryOpened(journal, now, "transfer-recovery-opened:" + id, recovery), by);
        metrics.transferReversalRefused(refusal.stripeCode());
        metrics.transferRecovery("opened");
        LOG.warn("Stripe refused to reverse {} of transfer {} for refund {} ({}): {} owes the platform {}", amount,
                transfer.stripeTransferId(), refundId, refusal.stripeCode(), transfer.orgId(), amount);
        return recovery;
    }

    @Override
    public List<TransferRecovery> ofRefund(UUID refundId) {
        return recoveries.ofRefund(refundId);
    }

    @Override
    public long outstanding(String orgId, String currency) {
        return recoveries.outstanding(orgId, currency);
    }

    @Override
    public Money recoverFrom(String orderId, String orgId, Money payable, Instant now) {
        Money taken = Money.zero(payable.currency());
        if (!payable.isPositive()) {
            return taken;
        }
        for (TransferRecovery r : recoveries.lockOutstanding(orgId, payable.currency())) {
            Money left = payable.minus(taken);
            if (!left.isPositive()) {
                break;
            }
            Money amount = r.outstanding().min(left);
            UUID id = UUID.randomUUID();
            UUID journal = Fingerprint.id("journal", "transfer-recovery-settled", id.toString());
            ledger.post(Postings.transferRecoverySettled(journal, now, "transfer-recovery-settled:" + id, orderId, r,
                    amount), SYSTEM);
            recoveries.insertDeduction(id, r.id(), orderId, amount, journal, now);
            recoveries.update(r.settledBy(amount, now));
            metrics.transferRecovery("settled");
            LOG.info("Order {}: {} of the refund recovery {} taken from {}'s transfer", orderId, amount, r.id(),
                    orgId);
            taken = taken.plus(amount);
        }
        return taken;
    }
}
