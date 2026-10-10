package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class FeeScheduleServiceImpl implements FeeScheduleService {

    private final FeeScheduleRepository schedules;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    FeeScheduleServiceImpl(FeeScheduleRepository schedules, MoneyTransactions tx, PaymentsAccess access,
                           Clock clock) {
        this.schedules = schedules;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public List<FeeSchedule> history() {
        access.reader();
        return schedules.all().stream().sorted(Comparator.comparingInt(FeeSchedule::version).reversed()).toList();
    }

    @Override
    public FeeSchedule activeAt(Instant at) {
        access.reader();
        Instant when = at == null ? Instant.now(clock) : at;
        return FeeSchedule.activeAt(schedules.all(), when).orElseThrow(() -> new NotFoundException(
                "payments.no-fee-schedule", "No fee schedule in force at " + when));
    }

    @Override
    public FeeSchedule create(NewFeeScheduleCommand cmd) {
        Actor actor = access.configure();
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            Instant effective = cmd.effectiveFrom() == null ? now : cmd.effectiveFrom();
            Optional<FeeSchedule> latest = schedules.latest();
            FeeSchedule.requireValidSuccessor(effective, latest, now);
            String currency = cmd.currency() == null ? "USD" : cmd.currency();
            FeeSchedule schedule = new FeeSchedule(UUID.randomUUID(), latest.map(FeeSchedule::version).orElse(0) + 1,
                    effective, currency, cmd.categoryBps(), cmd.defaultCommissionBps(), cmd.overrides(),
                    cmd.cardPctBps(), Money.of(cmd.cardFixedMinor(), currency),
                    Money.of(cmd.fbaPerUnitMinor(), currency), cmd.reverseCommissionOnRefund(), cmd.note(),
                    actor.subject(), now);
            schedules.insert(schedule);
            return schedule;
        });
    }
}
