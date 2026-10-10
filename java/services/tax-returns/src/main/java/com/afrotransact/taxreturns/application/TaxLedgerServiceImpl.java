package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxLedgerServiceImpl implements TaxLedgerService {

    static final String PAYMENTS_CONSUMER = "taxreturns.payments-refunded";

    private static final Logger LOG = LoggerFactory.getLogger(TaxLedgerServiceImpl.class);

    private final TaxTransactionRepository transactions;
    private final TaxLedger ledger;
    private final InboxDeduplicator inbox;
    private final Access access;
    private final Clock clock;

    TaxLedgerServiceImpl(TaxTransactionRepository transactions, TaxLedger ledger, InboxDeduplicator inbox,
                         Access access, Clock clock) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.inbox = inbox;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public LiabilityReport liability(YearMonth month) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return new LiabilityReport(month, null, transactions.liability(month, null));
    }

    @Override
    @Transactional(readOnly = true)
    public LiabilityReport sellerStatement(String sellerOrgId, YearMonth month) {
        Actor a = access.current();
        if (!(access.isStaff(a) && a.can(TaxReturnsPermissions.TAX_READ))) {
            access.orgOrStaff(TaxReturnsPermissions.PAYOUTS_READ, sellerOrgId);
        }
        return new LiabilityReport(month, sellerOrgId, transactions.liability(month, sellerOrgId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxTransaction> forOrder(UUID orderId) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return transactions.byOrder(orderId);
    }

    @Override
    @Transactional
    public int reverse(UUID orderId, String refundRef, String currency, List<ReversalLine> lines) {
        access.staff(TaxReturnsPermissions.REFUNDS_ISSUE);
        if (refundRef == null || refundRef.isBlank() || lines.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.invalid-reversal", "Give the refund reference and its lines");
        }
        if (lines.stream().anyMatch(l -> l.taxMinor() < 0 || l.taxableMinor() < 0)) {
            throw new ReturnRuleViolation("tax-returns.invalid-reversal", "Amounts are positive minor units");
        }
        for (ReversalLine l : lines) {
            transactions.sale(orderId, l.lineId()).ifPresent(sale -> {
                if (l.taxMinor() > sale.taxMinor()) {
                    throw new ReturnRuleViolation("tax-returns.reversal-exceeds-sale", "Line " + l.lineId()
                            + ": cannot reverse more tax than was collected (" + sale.taxMinor() + ")");
                }
            });
        }
        return ledger.recordRefund(orderId, refundRef, currency, lines, Instant.now(clock));
    }

    @Override
    @Transactional
    public boolean onPaymentRefunded(UUID eventId, PaymentRefunded e) {
        if (!inbox.firstDelivery(PAYMENTS_CONSUMER, eventId)) {
            return false;
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(e.orderId());
        } catch (IllegalArgumentException | NullPointerException ex) {
            LOG.debug("payments.refunded {} is not for a product order ({}); ignored", eventId, e.orderId());
            return true;
        }
        if ("FAILED".equalsIgnoreCase(e.status())) {
            int voided = ledger.voidRefund(e.refundId());
            LOG.info("Refund {} of order {} failed at payments: {} tax entries voided", e.refundId(), orderId, voided);
            return true;
        }
        List<ReversalLine> lines = e.lines().stream()
                .map(l -> new ReversalLine(l.lineId(), l.itemsMinor(), l.taxMinor())).toList();
        ledger.recordRefund(orderId, e.refundId(), e.currency(), lines,
                e.occurredAt() == null ? Instant.now(clock) : e.occurredAt());
        return true;
    }
}
