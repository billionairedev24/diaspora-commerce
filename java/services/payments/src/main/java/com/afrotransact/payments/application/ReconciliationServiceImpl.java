package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.payments.domain.ReconciliationDrift;
import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationReport;
import com.afrotransact.payments.domain.Reconciler;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.ReconciliationItemRepository;
import com.afrotransact.payments.port.ReconciliationRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.BalanceTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReconciliationServiceImpl implements ReconciliationService {

    /** Platform payouts to AfroTransact's bank and top-ups are outside the order ledger. */
    static final Set<String> IGNORED_TYPES = Set.of("payout", "payout_cancel", "payout_failure", "topup",
            "topup_reversal");
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationServiceImpl.class);
    private static final String NO_REF = "(no-stripe-ref)";

    private final StripeGateway stripe;
    private final LedgerRepository ledger;
    private final ReconciliationRepository runs;
    private final ReconciliationItemRepository items;
    private final Reconciler reconciler;
    private final PaymentsMetrics metrics;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    ReconciliationServiceImpl(StripeGateway stripe, LedgerRepository ledger, ReconciliationRepository runs,
                              ReconciliationItemRepository items, Reconciler reconciler, PaymentsMetrics metrics, PaymentsAccess access,
                              PaymentsSettings settings, Clock clock) {
        this.stripe = stripe;
        this.ledger = ledger;
        this.runs = runs;
        this.items = items;
        this.reconciler = reconciler;
        this.metrics = metrics;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReconciliationReport reconcile(LocalDate day) {
        access.configure();
        return run(day);
    }

    @Override
    @Transactional
    public ReconciliationReport runDaily(LocalDate day) {
        return run(day);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationReport> recent(int limit) {
        access.reader();
        return runs.recent(Math.max(1, Math.min(limit, 100)));
    }

    private ReconciliationReport run(LocalDate day) {
        Instant from = day.atStartOfDay(settings.payoutZone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(settings.payoutZone()).toInstant();
        String currency = settings.currency();
        Map<String, Long> stripeByRef = stripe.balanceTransactions(from, to).stream()
                .filter(t -> currency.equalsIgnoreCase(t.currency()))
                .filter(t -> !IGNORED_TYPES.contains(t.type().toLowerCase(Locale.ROOT)))
                .collect(Collectors.groupingBy(t -> Objects.requireNonNullElse(t.source(), t.id()),
                        Collectors.summingLong(BalanceTxn::net)));
        Map<String, Long> ledgerByRef = ledger.clearingByRef(from, to, currency).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey() == null ? NO_REF : e.getKey(), Map.Entry::getValue,
                        Long::sum));
        List<ReconciliationDrift> drifts = reconciler.compare(ledgerByRef, stripeByRef);
        long drift = drifts.stream().mapToLong(d -> Math.abs(d.difference())).sum();
        ReconciliationReport report = new ReconciliationReport(UUID.randomUUID(), from, to, currency,
                ledgerByRef.values().stream().mapToLong(Long::longValue).sum(),
                stripeByRef.values().stream().mapToLong(Long::longValue).sum(), drift, drifts, Instant.now(clock));
        runs.insert(report);
        openItems(report, drifts, from);
        metrics.reconciliationDrift(drift);
        if (drift != 0) {
            LOG.warn("Reconciliation {} found {} minor units of drift across {} Stripe objects", day, drift,
                    drifts.size());
        }
        return report;
    }

    /**
     * Each difference becomes an item Finance works through. A run of the same day replaces the earlier run's open
     * and explained items; an explanation carries over when the same difference is found again.
     */
    private void openItems(ReconciliationReport report, List<ReconciliationDrift> drifts, Instant windowStart) {
        List<ReconciliationItem> earlier = items.unresolvedOfWindow(windowStart);
        Map<String, ReconciliationItem> earlierByRef = earlier.stream()
                .collect(Collectors.toMap(ReconciliationItem::stripeRef, e -> e, (a, b) -> a));
        earlier.forEach(e -> items.update(e.superseded()));
        for (ReconciliationDrift d : drifts) {
            ReconciliationItem item = ReconciliationItem.open(UUID.randomUUID(), report.id(), windowStart, d,
                    report.currency(), report.createdAt());
            ReconciliationItem before = earlierByRef.get(d.stripeRef());
            items.insert(before == null ? item : item.carryOver(before));
        }
    }
}
