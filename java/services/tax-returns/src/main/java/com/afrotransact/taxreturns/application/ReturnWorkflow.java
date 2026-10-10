package com.afrotransact.taxreturns.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.StockStatus;
import com.afrotransact.taxreturns.port.CarrierLabels;
import com.afrotransact.taxreturns.port.InventoryGateway;
import com.afrotransact.taxreturns.port.RmaRepository;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Steps that call other services after an RMA change has committed: label or handover code after approval, refunds,
 * inventory postings. Each step re-reads the RMA, so retries from the job and concurrent requests stay consistent.
 */
@Component
class ReturnWorkflow {

    static final String SYSTEM = "SYSTEM:returns";
    private static final Logger LOG = LoggerFactory.getLogger(ReturnWorkflow.class);
    private static final String QR_PREFIX = "AFROTRANSACT-RETURN:";

    private final RmaRepository rmas;
    private final SellerPolicyRepository sellers;
    private final CarrierLabels labels;
    private final InventoryGateway inventory;
    private final RefundOrchestrator refunds;
    private final RmaEvents rmaEvents;
    private final ReturnPolicy policy;
    private final ReturnsSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ReturnWorkflow(RmaRepository rmas, SellerPolicyRepository sellers, CarrierLabels labels,
                   InventoryGateway inventory, RefundOrchestrator refunds, RmaEvents rmaEvents, ReturnPolicy policy,
                   ReturnsSettings settings, TransactionOperations tx, Clock clock) {
        this.rmas = rmas;
        this.sellers = sellers;
        this.labels = labels;
        this.inventory = inventory;
        this.refunds = refunds;
        this.rmaEvents = rmaEvents;
        this.policy = policy;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    /** After approval: buy the label (drop-off), issue the handover code (driver pickup) or refund (no return). */
    Rma afterApproval(UUID rmaId) {
        Rma rma = load(rmaId);
        if (!rma.status().approved()) {
            return rma;
        }
        return switch (rma.method()) {
            case DROP_OFF -> issueLabel(rma);
            case DRIVER_PICKUP -> arrangePickup(rma);
            case NO_RETURN -> refund(rma.id());
        };
    }

    private Rma issueLabel(Rma rma) {
        ReturnAddress to;
        String node;
        if (rma.fulfilment().afroTransactFulfilled()) {
            to = settings.returnCentre();
            node = settings.returnCentreNode();
        } else {
            to = sellers.find(rma.sellerOrgId()).map(SellerReturnPolicy::returnAddress).orElse(null);
            node = rma.sellerOrgId();
        }
        if (to == null || !to.complete() || rma.returnFrom() == null) {
            LOG.warn("Return {}: no return address for {} yet; label not issued", rma.number(), rma.sellerOrgId());
            return rma;
        }
        ReturnLabel label;
        try {
            label = labels.buyReturnLabel(new CarrierLabels.LabelRequest(rma.number(), node, rma.returnFrom(), to,
                    settings.labelWeightLb()), "rma-label-" + rma.id());
        } catch (UpstreamException ex) {
            LOG.warn("Return {}: label not bought ({} {}): {}; will retry", rma.number(), ex.status(), ex.code(),
                    ex.getMessage());
            return rma;
        }
        return tx.execute(s -> {
            Rma before = load(rma.id());
            if (!before.status().approved()) {
                return before;
            }
            Instant now = Instant.now(clock);
            String qr = QR_PREFIX + before.number() + ":" + label.trackingNumber();
            return rmaEvents.save(before, before.labelIssued(label, qr, now.plus(Duration.ofDays(policy.dropOffDays())),
                    now), TaxReturnsEvents.RMA_LABEL_ISSUED, SYSTEM, null);
        });
    }

    private Rma arrangePickup(Rma rma) {
        return tx.execute(s -> {
            Rma before = load(rma.id());
            if (!before.status().approved()) {
                return before;
            }
            Instant now = Instant.now(clock);
            String code = String.format("%06d", random.nextInt(1_000_000));
            String qr = QR_PREFIX + before.number() + ":" + code;
            return rmaEvents.save(before, before.pickupArranged(code, qr,
                    now.plus(Duration.ofDays(policy.dropOffDays())), now), TaxReturnsEvents.RMA_LABEL_ISSUED,
                    SYSTEM, null);
        });
    }

    /** Refunds the RMA's refundable lines now. Leaves the RMA refund state PENDING when payments is unavailable. */
    Rma refund(UUID rmaId) {
        Rma rma = load(rmaId);
        if (rma.refundState() == RefundState.ISSUED) {
            return rma;
        }
        Optional<Refund> prepared;
        try {
            prepared = refunds.prepareForRma(rma);
        } catch (UpstreamException ex) {
            LOG.warn("Return {}: refund not prepared ({}): {}; will retry", rma.number(), ex.status(),
                    ex.getMessage());
            return markRefund(rma.id(), RefundState.PENDING, ex.getMessage());
        } catch (ReturnRuleViolation ex) {
            LOG.warn("Return {}: refund refused: {}", rma.number(), ex.getMessage());
            return markRefund(rma.id(), RefundState.FAILED, ex.getMessage());
        }
        if (prepared.isEmpty()) {
            return rma;
        }
        if (rma.refundState() != RefundState.PENDING) {
            markRefund(rma.id(), RefundState.PENDING, null);
        }
        refunds.execute(prepared.get().id());
        return load(rma.id());
    }

    private Rma markRefund(UUID rmaId, RefundState state, String note) {
        return tx.execute(s -> {
            Rma before = load(rmaId);
            if (before.refundState() == state) {
                return before;
            }
            return rmaEvents.save(before, before.withRefundState(state, Instant.now(clock)), null, SYSTEM, note);
        });
    }

    /** Posts RETURN_RESTOCK / RETURN_DAMAGED for every pending line (Idempotency-Key per RMA line). */
    Rma postStock(UUID rmaId) {
        Rma rma = load(rmaId);
        Map<String, StockStatus> results = new HashMap<>();
        for (RmaLine l : rma.lines()) {
            if (l.stockStatus() != StockStatus.PENDING || l.condition() == null) {
                continue;
            }
            try {
                inventory.acceptReturn(rma.orderId().toString(), l.atin(), l.quantity(), l.condition(),
                        "Return " + rma.number() + ": " + l.reason().name()
                                + (l.inspectionNote() == null ? "" : " - " + l.inspectionNote()),
                        "rma-" + rma.id() + "-" + l.lineId());
                results.put(l.lineId(), StockStatus.POSTED);
            } catch (UpstreamException ex) {
                LOG.warn("Return {} line {}: inventory posting failed ({}): {}", rma.number(), l.lineId(),
                        ex.status(), ex.getMessage());
                if (!ex.retryable()) {
                    results.put(l.lineId(), StockStatus.FAILED);
                }
            }
        }
        if (results.isEmpty()) {
            return rma;
        }
        return tx.execute(s -> {
            Rma before = load(rmaId);
            List<RmaLine> lines = new ArrayList<>();
            for (RmaLine l : before.lines()) {
                StockStatus st = results.get(l.lineId());
                lines.add(st == null || l.stockStatus() != StockStatus.PENDING ? l : l.withStock(st));
            }
            return rmaEvents.save(before, before.withLines(lines, Instant.now(clock)), null, SYSTEM, null);
        });
    }

    Rma load(UUID id) {
        return rmas.findById(id).orElseThrow(() -> new NotFoundException("returns.rma-not-found", "Return not found"));
    }
}
