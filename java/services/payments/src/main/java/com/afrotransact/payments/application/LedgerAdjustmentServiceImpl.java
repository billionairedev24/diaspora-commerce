package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.Chart;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.JournalLine;
import com.afrotransact.payments.domain.LedgerAccount;
import com.afrotransact.payments.domain.LedgerAdjustment;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;
import com.afrotransact.payments.port.LedgerAdjustmentRepository;
import com.afrotransact.payments.port.ReconciliationItemRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class LedgerAdjustmentServiceImpl implements LedgerAdjustmentService {

    private static final int MAX_LIST = 500;

    private final LedgerAdjustmentRepository adjustments;
    private final ReconciliationItemRepository items;
    private final LedgerPoster poster;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    LedgerAdjustmentServiceImpl(LedgerAdjustmentRepository adjustments, ReconciliationItemRepository items,
                                LedgerPoster poster, MoneyTransactions tx, PaymentsAccess access,
                                PaymentsSettings settings, Clock clock) {
        this.adjustments = adjustments;
        this.items = items;
        this.poster = poster;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public LedgerAdjustment propose(ProposeAdjustment cmd) {
        String actor = access.adjuster().subject();
        return tx.serializable(() -> {
            String currency = settings.currency();
            String stripeRef = cmd.stripeRef();
            if (cmd.itemId() != null) {
                ReconciliationItem item = items.lock(cmd.itemId()).orElseThrow(() -> new NotFoundException(
                        "payments.reconciliation-item-not-found", "No reconciliation item " + cmd.itemId()));
                if (item.status() == ReconciliationStatus.SUPERSEDED) {
                    throw new PaymentsRuleViolation("payments.reconciliation-superseded",
                            "A later run replaced this difference; adjust the current one");
                }
                if (adjustments.ofItem(item.id()).stream().anyMatch(a -> a.status() == AdjustmentStatus.PROPOSED)) {
                    throw new PaymentsRuleViolation("payments.adjustment-pending",
                            "An adjustment for this difference is already waiting for approval");
                }
                stripeRef = item.stripeRef();
                currency = item.currency();
            }
            LedgerAdjustment adjustment = LedgerAdjustment.propose(UUID.randomUUID(), cmd.itemId(), stripeRef,
                    cmd.reason(), lines(cmd.lines(), currency), actor, Instant.now(clock));
            adjustments.insert(adjustment);
            return adjustment;
        });
    }

    @Override
    public LedgerAdjustment approve(UUID id, String note) {
        String actor = access.adjustmentApprover().subject();
        return tx.serializable(() -> {
            LedgerAdjustment current = adjustments.lock(id).orElseThrow(() -> notFound(id));
            Instant now = Instant.now(clock);
            Journal journal = current.toJournal(now);
            LedgerAdjustment posted = current.approve(actor, note, journal.id(), now);
            poster.post(journal, actor);
            adjustments.update(posted);
            if (posted.itemId() != null) {
                items.lock(posted.itemId()).ifPresent(i -> items.update(i.adjusted(actor, now)));
            }
            return posted;
        });
    }

    @Override
    public LedgerAdjustment reject(UUID id, String note) {
        String actor = access.adjustmentApprover().subject();
        return tx.serializable(() -> {
            LedgerAdjustment current = adjustments.lock(id).orElseThrow(() -> notFound(id));
            return adjustments.update(current.reject(actor, note, Instant.now(clock)));
        });
    }

    @Override
    public List<LedgerAdjustment> list(AdjustmentStatus status, int limit) {
        access.reader();
        return adjustments.list(status, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public LedgerAdjustment get(UUID id) {
        access.reader();
        return adjustments.find(id).orElseThrow(() -> notFound(id));
    }

    private static List<JournalLine> lines(List<Line> lines, String currency) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(l -> {
            Chart chart;
            try {
                chart = Chart.ofCode(l.account());
            } catch (IllegalArgumentException ex) {
                throw new PaymentsRuleViolation("payments.adjustment-account", "Unknown ledger account " + l.account(),
                        Map.of("account", "Unknown"));
            }
            LedgerAccount account;
            try {
                account = new LedgerAccount(chart, l.key());
            } catch (IllegalArgumentException ex) {
                throw new PaymentsRuleViolation("payments.adjustment-account", ex.getMessage(),
                        Map.of("key", "Invalid"));
            }
            if (l.amountMinor() <= 0) {
                throw new PaymentsRuleViolation("payments.adjustment-amount", "Amounts must be above zero",
                        Map.of("amountMinor", "Invalid"));
            }
            return new JournalLine(account, l.side(), Money.of(l.amountMinor(), currency), l.orgId());
        }).toList();
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.adjustment-not-found", "No ledger adjustment " + id);
    }
}
