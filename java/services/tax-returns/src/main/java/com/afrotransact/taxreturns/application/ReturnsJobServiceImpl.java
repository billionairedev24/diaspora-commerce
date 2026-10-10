package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.port.RefundRepository;
import com.afrotransact.taxreturns.port.RmaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ReturnsJobServiceImpl implements ReturnsJobService {

    private static final Logger LOG = LoggerFactory.getLogger(ReturnsJobServiceImpl.class);
    private static final int BATCH = 50;

    private final RmaRepository rmas;
    private final RefundRepository refunds;
    private final ReturnWorkflow workflow;
    private final RefundOrchestrator orchestrator;
    private final RmaEvents rmaEvents;
    private final ReturnsSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;

    ReturnsJobServiceImpl(RmaRepository rmas, RefundRepository refunds, ReturnWorkflow workflow,
                          RefundOrchestrator orchestrator, RmaEvents rmaEvents, ReturnsSettings settings,
                          TransactionOperations tx, Clock clock) {
        this.rmas = rmas;
        this.refunds = refunds;
        this.workflow = workflow;
        this.orchestrator = orchestrator;
        this.rmaEvents = rmaEvents;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Report runOnce() {
        Instant now = Instant.now(clock);
        int approved = 0;
        for (Rma rma : rmas.awaitingSellerBefore(now, BATCH)) {
            try {
                tx.executeWithoutResult(s -> {
                    Rma before = workflow.load(rma.id());
                    if (before.status() == RmaStatus.REQUESTED) {
                        rmaEvents.save(before, before.approve(true, "Seller did not respond in time", now),
                                TaxReturnsEvents.RMA_APPROVED, ReturnWorkflow.SYSTEM, "Seller approval SLA passed");
                    }
                });
                workflow.afterApproval(rma.id());
                approved++;
            } catch (RuntimeException ex) {
                LOG.warn("Auto-approval of return {} failed: {}", rma.number(), ex.getMessage());
            }
        }
        int labels = 0;
        for (Rma rma : rmas.approvedWithoutLabel(BATCH)) {
            labels += run("label", rma, () -> workflow.afterApproval(rma.id()));
        }
        int refunded = 0;
        for (Rma rma : rmas.awaitingRefund(BATCH)) {
            refunded += run("refund", rma, () -> workflow.refund(rma.id()));
        }
        for (Refund r : refunds.pendingBefore(now.minus(settings.refundRetryDelay()), BATCH)) {
            try {
                orchestrator.execute(r.id());
                refunded++;
            } catch (RuntimeException ex) {
                LOG.warn("Retry of refund {} failed: {}", r.id(), ex.getMessage());
            }
        }
        int stock = 0;
        for (Rma rma : rmas.withPendingStock(BATCH)) {
            stock += run("stock", rma, () -> workflow.postStock(rma.id()));
        }
        return new Report(approved, labels, refunded, stock);
    }

    private static int run(String what, Rma rma, Runnable step) {
        try {
            step.run();
            return 1;
        } catch (RuntimeException ex) {
            LOG.warn("Retry ({}) of return {} failed: {}", what, rma.number(), ex.getMessage());
            return 0;
        }
    }
}
