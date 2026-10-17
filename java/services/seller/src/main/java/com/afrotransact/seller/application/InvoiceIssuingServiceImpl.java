package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanChargePolicy;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.UsageDay;
import com.afrotransact.seller.domain.UsageMeter;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.PlanPolicyStateRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.SellerMetricRepository;
import com.afrotransact.seller.port.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class InvoiceIssuingServiceImpl implements InvoiceIssuingService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceIssuingServiceImpl.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    /** The metering job settles a day two days later; usage is billed up to that day. */
    private static final int USAGE_SETTLE_DAYS = 2;
    private static final String CURRENCY = "USD";

    private final PlanRepository plans;
    private final InvoiceRepository invoices;
    private final BillingGrantRepository grants;
    private final UsageRepository usage;
    private final ChargeGateEvaluator gates;
    private final PlanPolicyStateRepository policyState;
    private final SellerMetricRepository metrics;
    private final Clock clock;

    InvoiceIssuingServiceImpl(PlanRepository plans, InvoiceRepository invoices, BillingGrantRepository grants,
                              UsageRepository usage, ChargeGateEvaluator gates,
                              PlanPolicyStateRepository policyState, SellerMetricRepository metrics, Clock clock) {
        this.plans = plans;
        this.invoices = invoices;
        this.grants = grants;
        this.usage = usage;
        this.gates = gates;
        this.policyState = policyState;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Optional<Invoice> issue(Subscription sub, Instant start, Instant end, String reason) {
        Optional<Invoice> existing = invoices.findForPeriod(sub.orgId(), start);
        if (existing.isPresent()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        Plan plan = plans.find(sub.planCode(), sub.planVersion()).filter(p -> p.version() > 0)
                .orElseGet(() -> plans.find(sub.planCode()).orElseThrow());
        long planCents = plan.priceCents(sub.cadence());

        List<InvoiceLine> lines = new ArrayList<>();
        if (planCents > 0) {
            lines.add(new InvoiceLine(InvoiceLine.PLAN, plan.name() + " plan — " + label(sub) + " ("
                    + day(start) + " – " + day(end.minusSeconds(1)) + ")", planCents, null, null, start, end));
        }
        List<UsageDay> billedDays = new ArrayList<>();
        lines.addAll(usageLines(sub.orgId(), start, end, billedDays));
        long subtotal = lines.stream().mapToLong(InvoiceLine::amountCents).sum();
        if (subtotal <= 0 && billedDays.isEmpty()) {
            return Optional.empty();
        }
        subtotal = Math.max(0, subtotal);

        long feeLeft = planCents;
        long discount = 0;
        ChargeGate.Decision gate = planCents > 0 ? gates.decide(sub.orgId(), now).orElse(null) : null;
        if (gate != null && !gate.charge()) {
            lines.add(new InvoiceLine(InvoiceLine.WAIVER, gate.reason(), -planCents, null, null, start, end));
            discount += planCents;
            feeLeft = 0;
        }
        PlanChargePolicy.Decision policy = null;
        PlanChargePolicy chargePolicy = PlanChargePolicy.of(plan.limits());
        // Staff's per-organisation charge condition replaces the plan's own terms.
        if (feeLeft > 0 && gate == null && !chargePolicy.isPlainCharge()) {
            policy = chargePolicy.decide(sub.anchorDate() == null ? start : sub.anchorDate(), now,
                    c -> metricNow(sub.orgId(), c, chargePolicy.windowMonths(), sub.anchorDate(), now),
                    policyState.metAt(sub.orgId()).isPresent());
            if (!policy.charge()) {
                lines.add(new InvoiceLine(InvoiceLine.WAIVER, policy.reason(), -planCents, null, null, start, end));
                discount += planCents;
                feeLeft = 0;
            }
        }
        List<BillingGrant> used = new ArrayList<>();
        if (feeLeft > 0) {
            long base = feeLeft;
            for (BillingGrant coupon : grants.activeCoupons(sub.orgId())) {
                long off = Math.min(feeLeft, Math.round(base * coupon.percentOff().doubleValue() / 100.0));
                if (off <= 0) {
                    continue;
                }
                lines.add(new InvoiceLine(InvoiceLine.COUPON, coupon.summary() + " (" + coupon.percentOff()
                        .stripTrailingZeros().toPlainString() + "% off)", -off, null, null, start, end));
                discount += off;
                feeLeft -= off;
                used.add(coupon);
            }
        }
        long due = Math.max(0, subtotal - discount);
        boolean settled = due == 0;
        String id = "inv_" + UUID.randomUUID().toString().replace("-", "");
        String number = "AT-INV-" + LocalDate.ofInstant(now, ZoneOffset.UTC).getYear() + "-" + invoices.nextNumber();
        Invoice invoice = new Invoice(id, sub.orgId(), number, start, end, subtotal, discount, due, 0, CURRENCY,
                settled ? Invoice.PAID : Invoice.OPEN, reason, plan.code(), plan.version(), sub.cadence(), end,
                settled ? now : null, 0, null, null, now, lines, List.of());
        if (!invoices.insert(invoice)) {
            return Optional.empty();
        }
        used.forEach(c -> grants.useCouponOnce(c.id()));
        markUsageBilled(billedDays, number, now);
        gates.afterIssue(sub.orgId(), gate, now);
        if (policy != null && policy.nowMet()) {
            policyState.markMet(sub.orgId(), now);
        }
        LOG.info("Invoice {} of {}: {} due ({} subtotal, {} discount)", number, sub.orgId(), PlanPricing.usd(due),
                PlanPricing.usd(subtotal), PlanPricing.usd(discount));
        return Optional.of(invoices.findById(id).orElse(invoice));
    }

    @Override
    public Optional<Invoice> issueUpgrade(Subscription sub, Plan target, long amountCents, Instant now) {
        if (amountCents <= 0) {
            return Optional.empty();
        }
        Instant end = sub.currentPeriodEnd() == null ? now : sub.currentPeriodEnd();
        List<InvoiceLine> lines = List.of(new InvoiceLine(InvoiceLine.PRORATION, "Upgrade to " + target.name()
                + " — the rest of this period (" + day(now) + " – " + day(end.minusSeconds(1)) + ")", amountCents,
                null, null, now, end));
        String id = "inv_" + UUID.randomUUID().toString().replace("-", "");
        String number = "AT-INV-" + LocalDate.ofInstant(now, ZoneOffset.UTC).getYear() + "-" + invoices.nextNumber();
        Invoice invoice = new Invoice(id, sub.orgId(), number, now, end, amountCents, 0, amountCents, 0, CURRENCY,
                Invoice.OPEN, Invoice.REASON_UPDATE, target.code(), target.version(), sub.cadence(), now, null, 0,
                null, null, now, lines, List.of());
        invoices.insert(invoice);
        return invoices.findById(id);
    }

    /** The metric of a policy clause now: listings are a gauge, the rest are summed over the window or since start. */
    private long metricNow(String orgId, PlanChargePolicy.Clause clause, Integer windowMonths, Instant since,
                           Instant now) {
        if (clause.metric() == RewardMetric.ACTIVE_LISTINGS) {
            return metrics.activeListings(orgId);
        }
        LocalDate to = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate from = windowMonths != null ? to.minusMonths(windowMonths)
                : LocalDate.ofInstant(since == null ? now : since, ZoneOffset.UTC);
        return metrics.sum(orgId, clause.metric(), from, to);
    }

    /** Usage not yet billed up to the last settled day, one line per meter; the days are billed with the invoice. */
    private List<InvoiceLine> usageLines(String orgId, Instant start, Instant end, List<UsageDay> billedDays) {
        LocalDate through = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).minusDays(USAGE_SETTLE_DAYS);
        Map<UsageMeter, Long> units = new EnumMap<>(UsageMeter.class);
        for (UsageDay d : usage.unbilled(orgId, through)) {
            units.merge(d.meter(), d.delta(), Long::sum);
            billedDays.add(d);
        }
        List<InvoiceLine> out = new ArrayList<>();
        units.forEach((meter, qty) -> {
            long cents = meter.estimatedCents(Math.abs(qty)) * Long.signum(qty);
            if (cents != 0) {
                out.add(new InvoiceLine(InvoiceLine.USAGE, meter.label() + " — "
                        + meter.display(Math.abs(qty)).stripTrailingZeros().toPlainString() + " "
                        + meter.displayUnit(), cents, qty, meter, start, end));
            }
        });
        return out;
    }

    private void markUsageBilled(List<UsageDay> days, String number, Instant at) {
        for (UsageDay d : days) {
            usage.lock(d.orgId(), d.meter(), d.day()).ifPresent(locked -> usage.markReported(locked, d.quantity(),
                    d.revision() + 1, number + ":" + d.nextIdentifier(), d.delta(), at));
        }
    }

    private static String label(Subscription sub) {
        return switch (sub.cadence()) {
            case MONTHLY -> "monthly";
            case QUARTERLY -> "quarterly";
            case ANNUAL -> "yearly";
        };
    }

    private static String day(Instant at) {
        return DAY.format(at.atZone(ZoneOffset.UTC));
    }
}
