package com.afrotransact.seller.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.UsageDay;
import com.afrotransact.seller.domain.UsageMeter;
import com.afrotransact.seller.port.AdSpendSource;
import com.afrotransact.seller.port.AiUsageSource;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StorageUsageSource;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.UsageRepository;
import com.afrotransact.seller.port.UsageRepository.UsageRun;
import com.afrotransact.platform.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class UsageMeteringServiceImpl implements UsageMeteringService {

    static final String BILLED_CURRENCY = "USD";
    static final Duration LEASE = Duration.ofMinutes(30);

    private static final Logger LOG = LoggerFactory.getLogger(UsageMeteringServiceImpl.class);

    private final UsageRepository usage;
    private final SubscriptionRepository subs;
    private final StorageUsageSource storage;
    private final AdSpendSource ads;
    private final AiUsageSource ai;
    private final StripeBillingGateway stripe;
    private final AccessGuard access;
    private final TransactionOperations tx;
    private final Clock clock;

    UsageMeteringServiceImpl(UsageRepository usage, SubscriptionRepository subs, StorageUsageSource storage,
                             AdSpendSource ads, AiUsageSource ai, StripeBillingGateway stripe, AccessGuard access,
                             TransactionOperations tx, Clock clock) {
        this.usage = usage;
        this.subs = subs;
        this.storage = storage;
        this.ads = ads;
        this.ai = ai;
        this.stripe = stripe;
        this.access = access;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public UsageRunView run(LocalDate from, LocalDate to) {
        Actor actor = access.staffAny(SellerPermissions.USAGE);
        LocalDate today = today();
        Map<String, String> errors = new LinkedHashMap<>();
        if (from == null || to == null || to.isBefore(from)) {
            errors.put("to", "Must be on or after from");
        } else if (to.isAfter(today)) {
            errors.put("to", "Can't be after today (UTC)");
        } else if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            errors.put("to", "At most " + MAX_DAYS + " days after from");
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-usage-period", "Choose a period of up to " + MAX_DAYS
                    + " days ending today at the latest", errors);
        }
        return execute(from, to, "STAFF", actor.subject());
    }

    @Override
    public UsageRunView runScheduled() {
        LocalDate today = today();
        return execute(today.minusDays(2), today.minusDays(1), "JOB", "seller-service");
    }

    @Override
    public List<UsageRunView> runs() {
        access.staffAny(SellerPermissions.USAGE);
        return usage.runs(50).stream().map(UsageMeteringServiceImpl::view).toList();
    }

    @Override
    public UsageStatement statement(String orgId, LocalDate from, LocalDate to) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.USAGE);
        LocalDate start = from;
        LocalDate end = to;
        if (start == null || end == null) {
            LocalDate[] period = currentPeriod(orgId);
            start = start == null ? period[0] : start;
            end = end == null ? period[1] : end;
        }
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 400) {
            throw new SellerRuleViolation("seller.invalid-usage-period", "Choose a period of up to 400 days",
                    Map.of("to", "Must be on or after from, within 400 days"));
        }
        Map<UsageMeter, List<UsageDay>> byMeter = new EnumMap<>(UsageMeter.class);
        for (UsageMeter m : UsageMeter.values()) {
            byMeter.put(m, new ArrayList<>());
        }
        LocalDate first = start;
        LocalDate last = end;
        tx.execute(s -> usage.byOrg(orgId, first, last)).forEach(d -> byMeter.get(d.meter()).add(d));
        List<UsageStatement.MeterLine> lines = new ArrayList<>();
        long total = 0;
        for (Map.Entry<UsageMeter, List<UsageDay>> e : byMeter.entrySet()) {
            UsageMeter m = e.getKey();
            long quantity = e.getValue().stream().mapToLong(UsageDay::quantity).sum();
            long reported = e.getValue().stream().mapToLong(UsageDay::reportedQuantity).sum();
            long amount = m.estimatedCents(quantity);
            total += amount;
            List<UsageStatement.DayLine> days = e.getValue().stream().map(d -> new UsageStatement.DayLine(d.day(),
                    m.display(d.quantity()), m.display(d.reportedQuantity()), status(d), d.lastError())).toList();
            lines.add(new UsageStatement.MeterLine(m, m.display(quantity), m.display(reported),
                    m.microsPerDisplayUnit(), amount, days));
        }
        return new UsageStatement(orgId, start, end, BILLED_CURRENCY, lines, total);
    }

    // ------------------------------------------------------------------------------------------------------------

    private UsageRunView execute(LocalDate from, LocalDate to, String trigger, String actorId) {
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now(clock);
        Boolean leased = tx.execute(s -> usage.tryLease(runId, started, LEASE));
        if (!Boolean.TRUE.equals(leased)) {
            throw new SellerStateConflict("seller.usage-run-in-progress",
                    "Another metering run is in progress; try again in a few minutes");
        }
        UsageRun running = new UsageRun(runId, from, to, trigger, actorId, "RUNNING", 0, 0, 0, 0, List.of(),
                started, null);
        tx.executeWithoutResult(s -> usage.saveRun(running));
        try {
            List<String> failedSources = new ArrayList<>();
            int changed = 0;
            changed += collect(UsageMeter.FBA_STORAGE, from, to, "STORAGE", failedSources,
                    () -> samples(storage.cubicFeet(from, to), UsageMeter.FBA_STORAGE));
            changed += collect(UsageMeter.ADS_SPEND, from, to, "ADS", failedSources, () -> adSamples(from, to));
            changed += collect(UsageMeter.AI_CREDITS, from, to, "AI", failedSources, () -> aiSamples(from, to));
            Report report = report(from, to);
            String status = failedSources.size() == 3 ? "FAILED"
                    : failedSources.isEmpty() && report.pending() == 0 ? "COMPLETED" : "PARTIAL";
            UsageRun done = new UsageRun(runId, from, to, trigger, actorId, status, changed, report.reported(),
                    report.pending(), report.corrections(), failedSources, started, Instant.now(clock));
            tx.executeWithoutResult(s -> usage.saveRun(done));
            LOG.info("Metering {}..{} ({}): {} rows changed, {} reported, {} pending, {} corrections, sources failed {}",
                    from, to, status, changed, report.reported(), report.pending(), report.corrections(),
                    failedSources);
            return view(done);
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> usage.saveRun(new UsageRun(runId, from, to, trigger, actorId, "FAILED", 0, 0,
                    0, 0, List.of(), started, Instant.now(clock))));
            throw e;
        } finally {
            tx.executeWithoutResult(s -> usage.releaseLease(runId));
        }
    }

    /** One sample: quantity in meter units of one organisation on one day. */
    private record Sample(String orgId, LocalDate day, long quantity) {
    }

    private List<Sample> samples(List<StorageUsageSource.StorageSample> rows, UsageMeter meter) {
        return rows.stream().map(r -> new Sample(r.orgId(), r.day(), meter.unitsOf(r.cubicFeet()))).toList();
    }

    private List<Sample> adSamples(LocalDate from, LocalDate to) {
        List<Sample> out = new ArrayList<>();
        for (AdSpendSource.AdSpendSample s : ads.spend(from, to)) {
            if (s.currency() == null || !BILLED_CURRENCY.equals(s.currency().toUpperCase(Locale.ROOT))) {
                LOG.warn("Ads spend of {} on {} in {} is not billed in {}; skipped", s.orgId(), s.day(), s.currency(),
                        BILLED_CURRENCY);
                continue;
            }
            out.add(new Sample(s.orgId(), s.day(), Math.max(0, s.spendMinor())));
        }
        return out;
    }

    private List<Sample> aiSamples(LocalDate from, LocalDate to) {
        return ai.credits(from, to).stream().map(s -> new Sample(s.orgId(), s.day(), Math.max(0, s.credits())))
                .toList();
    }

    /**
     * Reads one source and makes the stored days of [from, to] equal to it: present days are upserted, stored days
     * the source no longer reports drop to 0. A source failure leaves that meter's rows untouched.
     */
    private int collect(UsageMeter meter, LocalDate from, LocalDate to, String source, List<String> failed,
                        Supplier<List<Sample>> read) {
        List<Sample> samples;
        try {
            samples = read.get();
        } catch (GatewayException e) {
            LOG.warn("Usage source {} unavailable for {}..{}: {}", source, from, to, e.getMessage());
            failed.add(source);
            return 0;
        }
        Map<String, Long> totals = new HashMap<>();
        for (Sample s : samples) {
            if (s.orgId() == null || s.day() == null || s.day().isBefore(from) || s.day().isAfter(to)) {
                continue;
            }
            totals.merge(s.orgId() + "|" + s.day(), s.quantity(), Long::sum);
        }
        Set<String> orgIds = new HashSet<>();
        totals.keySet().forEach(k -> orgIds.add(k.substring(0, k.indexOf('|'))));
        Instant now = Instant.now(clock);
        Integer changed = tx.execute(s -> {
            Set<String> known = usage.knownOrgs(orgIds);
            int n = 0;
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                String org = e.getKey().substring(0, e.getKey().indexOf('|'));
                if (!known.contains(org)) {
                    continue;
                }
                LocalDate day = LocalDate.parse(e.getKey().substring(e.getKey().indexOf('|') + 1));
                n += usage.upsert(org, meter, day, e.getValue(), now) ? 1 : 0;
            }
            for (UsageDay d : usage.byMeter(meter, from, to)) {
                if (d.quantity() != 0 && !totals.containsKey(d.orgId() + "|" + d.day())) {
                    n += usage.upsert(d.orgId(), meter, d.day(), 0, now) ? 1 : 0;
                }
            }
            return n;
        });
        return changed == null ? 0 : changed;
    }

    private record Report(int reported, int pending, int corrections) {
    }

    /** Sends a meter event for every positive difference between stored usage and what Stripe has. */
    private Report report(LocalDate from, LocalDate to) {
        int reported = 0;
        int pending = 0;
        int corrections = 0;
        Map<String, List<UsageDay>> byOrg = new LinkedHashMap<>();
        tx.execute(s -> usage.unreported(from, to)).forEach(d ->
                byOrg.computeIfAbsent(d.orgId(), k -> new ArrayList<>()).add(d));
        for (Map.Entry<String, List<UsageDay>> e : byOrg.entrySet()) {
            Optional<Subscription> sub = tx.execute(s -> subs.find(e.getKey()));
            String customer = sub == null ? null : sub.map(Subscription::stripeCustomerId).orElse(null);
            if (customer != null) {
                ensureMeteredItems(sub.get());
            }
            for (UsageDay d : e.getValue()) {
                Instant now = Instant.now(clock);
                if (d.needsCorrection()) {
                    corrections++;
                    tx.executeWithoutResult(s -> usage.markError(d, "Usage dropped by " + d.meter().display(-d.delta())
                            + " " + d.meter().displayUnit() + " after it was billed; issue a credit", now));
                    continue;
                }
                if (customer == null) {
                    pending++;
                    tx.executeWithoutResult(s -> usage.markError(d, "No Stripe customer yet", now));
                    continue;
                }
                if (!d.reportable(now)) {
                    pending++;
                    tx.executeWithoutResult(s -> usage.markError(d, "Older than " + UsageDay.STRIPE_MAX_AGE_DAYS
                            + " days: Stripe no longer accepts meter events for it", now));
                    continue;
                }
                if (send(d, customer)) {
                    reported++;
                } else {
                    pending++;
                }
            }
        }
        return new Report(reported, pending, corrections);
    }

    private boolean send(UsageDay d, String customer) {
        Instant now = Instant.now(clock);
        String identifier = d.nextIdentifier();
        try {
            stripe.reportMeterEvent(d.meter().stripeEventName(), customer, d.delta(), identifier,
                    d.eventTimestamp(now));
        } catch (GatewayException e) {
            LOG.warn("Meter event {} failed: {}", identifier, e.getMessage());
            tx.executeWithoutResult(s -> usage.markError(d, e.getMessage(), now));
            return false;
        }
        tx.executeWithoutResult(s -> usage.lock(d.orgId(), d.meter(), d.day()).ifPresent(locked -> usage.markReported(
                locked, d.quantity(), d.revision() + 1, identifier, d.delta(), customer, now)));
        return true;
    }

    /** Adds the metered prices to the paid subscription once, so Stripe invoices carry the usage lines. */
    private void ensureMeteredItems(Subscription sub) {
        if (sub.stripeSubscriptionId() == null) {
            return;
        }
        Set<UsageMeter> present = tx.execute(s -> usage.meteredItems(sub.orgId(), sub.stripeSubscriptionId()));
        for (UsageMeter m : UsageMeter.values()) {
            if (present != null && present.contains(m)) {
                continue;
            }
            try {
                String item = stripe.addSubscriptionItem(sub.stripeSubscriptionId(), m.priceLookupKey(),
                        "metered-item-" + sub.orgId() + "-" + m.name() + "-" + sub.stripeSubscriptionId());
                Instant now = Instant.now(clock);
                tx.executeWithoutResult(s -> usage.saveMeteredItem(sub.orgId(), m, sub.stripeSubscriptionId(), item,
                        now));
            } catch (GatewayException e) {
                LOG.warn("Could not add metered price {} to {}: {}", m.priceLookupKey(), sub.stripeSubscriptionId(),
                        e.getMessage());
            }
        }
    }

    private LocalDate[] currentPeriod(String orgId) {
        LocalDate today = today();
        Optional<Subscription> sub = tx.execute(s -> subs.find(orgId));
        if (sub != null && sub.isPresent() && sub.get().currentPeriodStart() != null) {
            LocalDate start = LocalDate.ofInstant(sub.get().currentPeriodStart(), ZoneOffset.UTC);
            LocalDate end = sub.get().currentPeriodEnd() == null ? today
                    : LocalDate.ofInstant(sub.get().currentPeriodEnd(), ZoneOffset.UTC).minusDays(1);
            if (!start.isAfter(today)) {
                return new LocalDate[] {start, end.isBefore(start) ? start : end};
            }
        }
        return new LocalDate[] {today.withDayOfMonth(1), today};
    }

    private static String status(UsageDay d) {
        if (d.needsCorrection()) {
            return "CORRECTION";
        }
        return d.delta() == 0 ? "REPORTED" : "PENDING";
    }

    private LocalDate today() {
        return LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static UsageRunView view(UsageRun r) {
        return new UsageRunView(r.id(), r.from(), r.to(), r.trigger(), r.status(), r.rowsChanged(), r.reported(),
                r.pending(), r.corrections(), r.failedSources(), r.startedAt(), r.finishedAt());
    }

    /** Statement amounts in display units (visible for tests). */
    static BigDecimal display(UsageMeter meter, long units) {
        return meter.display(units);
    }
}
