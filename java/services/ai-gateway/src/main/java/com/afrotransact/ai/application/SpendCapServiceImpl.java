package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.SpendCapPreconditionException;
import com.afrotransact.ai.domain.SpendCapSettings;
import com.afrotransact.ai.port.SpendCapStore;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class SpendCapServiceImpl implements SpendCapService {

    private static final Logger LOG = LoggerFactory.getLogger(SpendCapServiceImpl.class);
    static final int MIN_REASON = 3;
    static final int MAX_REASON = 300;

    private final AiSpendCapPolicy policy;
    private final SpendCapStore store;
    private final UsageLedger ledger;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    SpendCapServiceImpl(AiSpendCapPolicy policy, SpendCapStore store, UsageLedger ledger, DomainEventPublisher events,
                        TransactionOperations tx, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.ledger = ledger;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public SpendCapView view(Caller caller) {
        requireStaff(caller);
        if (!caller.can(AiPermissions.MANAGE) && !caller.can(AiPermissions.CAP_MANAGE)) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + AiPermissions.MANAGE);
        }
        return build(caller);
    }

    @Override
    public SpendCapView update(Caller caller, Long expectedVersion, long capMicros, String reason) {
        requireStaff(caller);
        caller.require(AiPermissions.CAP_MANAGE);
        if (expectedVersion == null || expectedVersion < 0) {
            throw SpendCapPreconditionException.required();
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON || why.length() > MAX_REASON) {
            throw new AiRuleViolation("ai.spend-cap.reason-invalid",
                    "Say why in " + MIN_REASON + " to " + MAX_REASON + " characters; it is logged with your name");
        }
        long ceiling = policy.current().ceilingMicros();
        if (capMicros < 0 || capMicros % SpendCapSettings.STEP_MICROS != 0) {
            throw new AiRuleViolation("ai.spend-cap.invalid", "The cap is a whole number of cents, 0 or more");
        }
        if (capMicros > ceiling) {
            throw new AiRuleViolation("ai.spend-cap.above-ceiling",
                    "The cap cannot be more than the ceiling set on the deployment (" + usd(ceiling) + ")");
        }
        Instant now = Instant.now(clock);
        SpendCapStore.Saved saved = tx.execute(status -> {
            // read the value being replaced from the database, not the cache: the audit shows what was really there
            Long previous = store.current().map(SpendCapStore.Saved::capMicros).orElse(null);
            SpendCapStore.Saved s = store.save(capMicros, expectedVersion, caller.subject(), why, now);
            events.publish(AiEvents.SPEND_CAP_CHANGED, AiEvents.AGGREGATE_SPEND_CAP, String.valueOf(s.version()),
                    new SpendCapChangedPayload(s.version(), previous == null ? "DEFAULT" : "SAVED",
                            previous == null ? policy.current().defaultMicros() : previous,
                            s.capMicros(), caller.subject(), why, now));
            return s;
        });
        policy.invalidate();
        LOG.info("AI daily spend cap changed to {} micro-dollars (version {}) by {}", saved.capMicros(), saved.version(),
                caller.subject());
        return build(caller);
    }

    private SpendCapView build(Caller caller) {
        AiSpendCapPolicy.Cap cap = policy.current();
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        long spent = ledger.globalTotals(day).costMicros();
        List<Change> history = store.history(HISTORY_LIMIT).stream().map(c -> new Change(c.id(), c.version(),
                c.previousMicros(), c.previousWasDefault(), c.newMicros(), c.changedBy(), c.changedAt(), c.reason()))
                .toList();
        return new SpendCapView(cap.capMicros(), cap.source(), cap.defaultMicros(), cap.ceilingMicros(), cap.version(),
                cap.changedBy(), cap.changedAt(), cap.reason(), day, spent, Math.max(0, cap.capMicros() - spent),
                day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), caller.can(AiPermissions.CAP_MANAGE), history);
    }

    private static String usd(long micros) {
        long cents = micros / SpendCapSettings.STEP_MICROS;
        return String.format(Locale.ROOT, "USD %d.%02d", cents / 100, cents % 100);
    }

    private static void requireStaff(Caller caller) {
        if (!"STAFF".equals(caller.principalType())) {
            throw new ForbiddenActionException("auth.missing-permission", "Only staff may use the AI spend cap");
        }
    }

    /** What trust-ops copies into the audit log: who, when, why and the value before and after. */
    record SpendCapChangedPayload(long version, String previousSource, Long previousMicros, long newMicros,
                                  String changedBy, String reason, Instant at) {
    }
}
