package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class FeeScheduleServiceImpl implements FeeScheduleService {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.payments.fee-schedules");

    private final FeeScheduleRepository schedules;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    FeeScheduleServiceImpl(FeeScheduleRepository schedules, MoneyTransactions tx, PaymentsAccess access,
                           PaymentsSettings settings, Clock clock) {
        this.schedules = schedules;
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
    public EffectiveFees effective(String category, String orgId, Instant at) {
        if (category == null || category.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "category is required",
                    Map.of("category", "Required"));
        }
        FeeSchedule schedule = activeAt(at);
        String org = orgId == null || orgId.isBlank() ? null : orgId.trim();
        return schedule.effectiveFor(org, category, settings.retailOrgId());
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
            FeeSchedule schedule = new FeeSchedule(UUID.randomUUID(), FeeSchedule.nextVersion(history),
                    effective, currency, cmd.categoryBps(), cmd.defaultCommissionBps(), cmd.overrides(),
                    cmd.cardPctBps(), Money.of(cmd.cardFixedMinor(), currency),
                    Money.of(cmd.fbaPerUnitMinor(), currency), cmd.reverseCommissionOnRefund(), cmd.note(),
                    actor.subject(), now);
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
