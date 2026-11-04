package com.afrotransact.payments.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.payments.domain.AppliedRate;
import com.afrotransact.payments.domain.DeliveryPricing;
import com.afrotransact.payments.domain.DeliveryQuote;
import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateType;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.RateSource;
import com.afrotransact.payments.domain.RateTable;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.NegotiatedRateRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class FeeScheduleServiceImpl implements FeeScheduleService {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.payments.fee-schedules");

    private final FeeScheduleRepository schedules;
    private final NegotiatedRateRepository negotiated;
    private final OrgPlanService plans;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    FeeScheduleServiceImpl(FeeScheduleRepository schedules, NegotiatedRateRepository negotiated, OrgPlanService plans,
                           MoneyTransactions tx, PaymentsAccess access, PaymentsSettings settings, Clock clock) {
        this.schedules = schedules;
        this.negotiated = negotiated;
        this.plans = plans;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public List<FeeSchedule> history() {
        access.feeReader();
        return schedules.all().stream().sorted(Comparator.comparingInt(FeeSchedule::version).reversed()).toList();
    }

    @Override
    public FeeSchedule activeAt(Instant at) {
        access.feeReader();
        Instant when = at == null ? Instant.now(clock) : at;
        return FeeSchedule.activeAt(schedules.all(), when).orElseThrow(() -> new NotFoundException(
                "payments.no-fee-schedule", "No fee schedule in force at " + when));
    }

    @Override
    public EffectiveFees effective(FeeKind kind, String category, String orgId, Instant at) {
        if (category == null || category.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "category is required",
                    Map.of("category", "Required"));
        }
        Instant when = at == null ? Instant.now(clock) : at;
        FeeSchedule schedule = activeAt(when);
        String org = orgId == null || orgId.isBlank() ? null : orgId.trim();
        FeeKind k = kind == null ? FeeKind.PRODUCT : kind;
        if (org == null) {
            return schedule.effectiveFor(k, null, category, null, List.of(), when, settings.retailOrgId());
        }
        return schedule.effectiveFor(k, org, category, plans.planOf(org).orElse(null),
                negotiated.forOrgs(List.of(org)), when, settings.retailOrgId());
    }

    @Override
    public RateCard rateCard(String orgId, FeeKind kind, Instant at) {
        if (orgId == null || orgId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "orgId is required",
                    Map.of("orgId", "Required"));
        }
        String org = orgId.trim();
        Actor actor = access.current();
        if (PaymentsAccess.isStaff(actor)) {
            access.feeReader();
        } else {
            access.balanceViewer(org);
        }
        Instant when = at == null ? Instant.now(clock) : at;
        FeeSchedule schedule = FeeSchedule.activeAt(schedules.all(), when).orElseThrow(() -> new NotFoundException(
                "payments.no-fee-schedule", "No fee schedule in force at " + when));
        FeeKind k = kind == null ? FeeKind.PRODUCT : kind;
        String plan = plans.planOf(org).orElse(null);
        NegotiatedRateType type = NegotiatedRateType.of(k);
        List<NegotiatedRate> own = negotiated.forOrgs(List.of(org)).stream().filter(n -> n.type() == type
                || k == FeeKind.PRODUCT && n.type() == NegotiatedRateType.FBA_FEE).toList();
        RateTable table = schedule.section(k);
        Set<String> categories = new TreeSet<>(table.categoryBps().keySet());
        if (plan != null) {
            categories.addAll(table.planBps().getOrDefault(plan, Map.of()).keySet());
        }
        if (k == FeeKind.PRODUCT) {
            schedule.overrides().stream().filter(o -> o.orgId().equals(org)).forEach(o -> categories.add(o.category()));
        }
        own.forEach(n -> categories.add(n.category()));
        categories.remove(RateTable.ANY);
        String retail = settings.retailOrgId();
        List<AppliedRate> rates = categories.stream()
                .map(c -> schedule.rateFor(k, org, c, plan, own, when, retail)).toList();
        AppliedRate other = schedule.rateFor(k, org, "", plan, own, when, retail);
        boolean isRetail = other.source() == RateSource.RETAIL;
        List<NegotiatedRate> current = own.stream()
                .filter(n -> n.activeAt(when) || n.startsAt().isAfter(when)
                        && (n.effectiveEnd() == null || n.effectiveEnd().isAfter(n.startsAt())))
                .sorted(Comparator.comparing(NegotiatedRate::startsAt)).toList();
        return new RateCard(org, k, schedule.id(), schedule.version(), schedule.effectiveFrom(), when,
                schedule.currency(), plan, other, rates, isRetail ? List.of() : current, schedule.cardPctBps(),
                schedule.cardFixed(), isRetail || k == FeeKind.SERVICE ? Money.zero(schedule.currency())
                : FeeSchedule.negotiatedFba(org, own, when).map(n -> Money.of(n.amountMinor(), schedule.currency()))
                .orElse(schedule.fbaPerUnitFor(plan)), schedule.reverseCommissionOnRefund(), isRetail);
    }

    @Override
    public DeliveryQuote quoteDelivery(String sellerOrgId, BigDecimal distanceMiles, int packages, Instant at) {
        access.deliveryQuoter();
        if (sellerOrgId == null || sellerOrgId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "sellerOrgId is required",
                    Map.of("sellerOrgId", "Required"));
        }
        if (distanceMiles == null) {
            throw new PaymentsRuleViolation("payments.invalid-request", "distanceMiles is required",
                    Map.of("distanceMiles", "Required"));
        }
        Instant when = at == null ? Instant.now(clock) : at;
        FeeSchedule schedule = FeeSchedule.activeAt(schedules.all(), when).orElseThrow(() -> new NotFoundException(
                "payments.no-fee-schedule", "No fee schedule in force at " + when));
        return schedule.quoteDelivery(sellerOrgId.trim(), distanceMiles, packages, settings.retailOrgId());
    }

    @Override
    public FeeSchedule create(NewFeeScheduleCommand cmd) {
        Actor actor = access.configure();
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            Instant effective = cmd.effectiveFrom() == null ? now : cmd.effectiveFrom();
            List<FeeSchedule> history = schedules.all();
            Optional<FeeSchedule> latest = FeeSchedule.latestLive(history);
            FeeSchedule.requireValidSuccessor(effective, latest, now);
            String currency = cmd.currency() == null ? "USD" : cmd.currency();
            DeliveryPricing delivery = cmd.deliveryPricing() != null ? cmd.deliveryPricing().toPricing(currency)
                    : latest.map(FeeSchedule::deliveryPricing)
                    .filter(d -> d.currency().equals(Money.zero(currency).currency())).orElse(null);
            Map<String, Map<String, Integer>> planBps = cmd.planBps() != null ? cmd.planBps()
                    : latest.map(FeeSchedule::planBps).orElse(Map.of());
            RateTable connectionFee = cmd.connectionFee() != null ? cmd.connectionFee()
                    : latest.map(FeeSchedule::connectionFee).orElse(null);
            Map<String, Long> planFba = cmd.planFbaMinor() != null ? cmd.planFbaMinor()
                    : latest.map(FeeSchedule::planFbaMinor).orElse(Map.of());
            FeeSchedule schedule = new FeeSchedule(UUID.randomUUID(), FeeSchedule.nextVersion(history),
                    effective, currency, cmd.categoryBps(), cmd.defaultCommissionBps(), cmd.overrides(),
                    cmd.cardPctBps(), Money.of(cmd.cardFixedMinor(), currency),
                    Money.of(cmd.fbaPerUnitMinor(), currency), cmd.reverseCommissionOnRefund(), cmd.note(),
                    actor.subject(), now, null, delivery, planBps, connectionFee, planFba);
            schedules.insert(schedule);
            AUDIT.info("fee schedule v{} ({}) created by {}, effective {}", schedule.version(), schedule.id(),
                    actor.subject(), schedule.effectiveFrom());
            return schedule;
        });
    }

    @Override
    public FeeSchedule cancel(UUID id, String reason) {
        Actor actor = access.configure();
        return tx.serializable(() -> {
            FeeSchedule schedule = schedules.findById(id).orElseThrow(() -> new NotFoundException(
                    "payments.fee-schedule-not-found", "No fee schedule " + id));
            FeeSchedule cancelled = schedule.cancel(Instant.now(clock), actor.subject(), reason);
            schedules.insertCancellation(id, cancelled.cancellation());
            AUDIT.info("fee schedule v{} ({}) cancelled by {} before {}: {}", cancelled.version(), id,
                    actor.subject(), cancelled.effectiveFrom(), cancelled.cancellation().reason());
            return cancelled;
        });
    }
}
