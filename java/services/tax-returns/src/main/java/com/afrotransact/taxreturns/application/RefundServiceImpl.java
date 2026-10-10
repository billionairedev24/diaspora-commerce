package com.afrotransact.taxreturns.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundStatus;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class RefundServiceImpl implements RefundService {

    private static final int MAX_REASON = 500;

    private final RefundOrchestrator orchestrator;
    private final RefundRepository refunds;
    private final OrderSnapshotRepository orders;
    private final Access access;
    private final TransactionOperations tx;

    RefundServiceImpl(RefundOrchestrator orchestrator, RefundRepository refunds, OrderSnapshotRepository orders,
                      Access access, TransactionOperations tx) {
        this.orchestrator = orchestrator;
        this.refunds = refunds;
        this.orders = orders;
        this.access = access;
        this.tx = tx;
    }

    @Override
    public Refund adjust(UUID orderId, List<AdjustmentLine> lines, String reason, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new ReturnRuleViolation("returns.idempotency-key-required", "Send an Idempotency-Key header");
        }
        if (lines == null || lines.isEmpty() || reason == null || reason.isBlank() || reason.length() > MAX_REASON) {
            throw new ReturnRuleViolation("returns.invalid-refund", "Give the lines and a reason (max 500 characters)");
        }
        PlacedOrder order = orders.find(orderId).orElseThrow(() -> new NotFoundException("returns.order-not-found",
                "Order not found"));
        Set<String> sellers = new LinkedHashSet<>();
        for (AdjustmentLine l : lines) {
            String seller = order.shipments().stream()
                    .filter(s -> s.lines().stream().anyMatch(x -> x.lineId().equals(l.lineId())))
                    .map(PlacedOrder.Shipment::sellerOrgId).findFirst()
                    .orElseThrow(() -> new ReturnRuleViolation("returns.unknown-line", "Line " + l.lineId()
                            + " is not part of order " + order.orderNumber()));
            sellers.add(seller);
        }
        for (String seller : sellers) {
            access.orgOrStaff(TaxReturnsPermissions.REFUNDS_ISSUE, seller);
        }
        Refund prepared = orchestrator.prepareAdjustment(orderId, lines, reason.trim(), "adj:" + order.orderId()
                + ":" + idempotencyKey);
        return orchestrator.execute(prepared.id());
    }

    @Override
    public Refund get(UUID id) {
        reader();
        return find(id);
    }

    @Override
    public List<Refund> forOrder(UUID orderId) {
        reader();
        return refunds.findByOrder(orderId);
    }

    @Override
    public List<Refund> forRma(UUID rmaId) {
        reader();
        return refunds.findByRma(rmaId);
    }

    @Override
    public Refund retry(UUID id) {
        access.staff(TaxReturnsPermissions.REFUNDS_ISSUE);
        Refund r = find(id);
        if (r.status() == RefundStatus.ISSUED) {
            return r;
        }
        if (r.status() == RefundStatus.FAILED) {
            tx.execute(s -> refunds.update(r.reopened()));
        }
        return orchestrator.execute(id);
    }

    private Refund find(UUID id) {
        return refunds.findById(id).orElseThrow(() -> new NotFoundException("returns.refund-not-found",
                "Refund not found"));
    }

    private void reader() {
        Actor a = access.current();
        boolean ok = access.isStaff(a) && (a.can(TaxReturnsPermissions.REFUNDS_ISSUE)
                || a.can(TaxReturnsPermissions.RETURNS_MANAGE));
        if (!ok) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + TaxReturnsPermissions.REFUNDS_ISSUE);
        }
    }
}
