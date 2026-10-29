package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;
import com.afrotransact.payments.port.LedgerAdjustmentRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.ReconciliationItemRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class ReconciliationWorkflowServiceImpl implements ReconciliationWorkflowService {

    private static final int MAX_LIST = 500;

    private final ReconciliationItemRepository items;
    private final LedgerAdjustmentRepository adjustments;
    private final LedgerRepository ledger;
    private final StripeLookupService lookup;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    ReconciliationWorkflowServiceImpl(ReconciliationItemRepository items, LedgerAdjustmentRepository adjustments,
                                      LedgerRepository ledger, StripeLookupService lookup, MoneyTransactions tx,
                                      PaymentsAccess access, Clock clock) {
        this.items = items;
        this.adjustments = adjustments;
        this.ledger = ledger;
        this.lookup = lookup;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public List<ReconciliationItem> items(ReconciliationStatus status, int limit) {
        access.reader();
        return items.list(status, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public ItemDetail item(UUID id) {
        access.reader();
        ReconciliationItem item = items.find(id).orElseThrow(() -> notFound(id));
        return new ItemDetail(item, lookup.lookup(item.stripeRef(), null), ledger.journalsByStripeRef(item.stripeRef()),
                adjustments.ofItem(id));
    }

    @Override
    public ReconciliationItem explain(UUID id, String note) {
        String actor = access.adjuster().subject();
        return tx.serializable(() -> {
            ReconciliationItem item = items.lock(id).orElseThrow(() -> notFound(id));
            return items.update(item.explain(actor, note, Instant.now(clock)));
        });
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.reconciliation-item-not-found", "No reconciliation item " + id);
    }
}
