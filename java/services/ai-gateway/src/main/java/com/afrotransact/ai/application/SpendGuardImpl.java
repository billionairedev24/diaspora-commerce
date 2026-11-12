package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.domain.RateLimitedException;
import com.afrotransact.ai.domain.SpendLimits;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.CallRateLimiter;
import com.afrotransact.ai.port.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class SpendGuardImpl implements SpendGuard {

    /** Scope of the gateway-wide daily total in {@code BudgetExceededException} and the metrics. */
    static final String GLOBAL_SCOPE = "global";
    /** Scope of one caller's daily share. */
    static final String CALLER_SCOPE = "caller";

    private static final Logger LOG = LoggerFactory.getLogger(SpendGuardImpl.class);

    private final UsageLedger ledger;
    private final CallRateLimiter limiter;
    private final SpendLimits limits;
    private final AiMetrics metrics;
    private final Clock clock;

    SpendGuardImpl(UsageLedger ledger, CallRateLimiter limiter, SpendLimits limits, AiMetrics metrics, Clock clock) {
        this.ledger = ledger;
        this.limiter = limiter;
        this.limits = limits;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void check(Caller caller, AiFeature feature) {
        long wait = limiter.acquire(caller.subject(), limits.callsPerMinute(caller.principalType()));
        if (wait > 0) {
            metrics.budget(feature.key(), "rate_limited");
            LOG.info("AI call rate exceeded: feature={} principalType={}", feature.key(), caller.principalType());
            throw new RateLimitedException(wait);
        }
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        if (limits.globalDailyCostMicros() > 0
                && ledger.globalTotals(today).costMicros() >= limits.globalDailyCostMicros()) {
            metrics.budget(feature.key(), "deny_global");
            LOG.warn("AI daily spend ceiling reached: feature={}", feature.key());
            throw new BudgetExceededException(feature.key(), GLOBAL_SCOPE,
                    "The daily AI spend limit has been reached; try again tomorrow");
        }
        if (limits.callerDailyCostMicros() > 0
                && ledger.callerTotals(caller.subject(), today).costMicros() >= limits.callerDailyCostMicros()) {
            metrics.budget(feature.key(), "deny_caller");
            LOG.info("AI caller share reached: feature={} principalType={}", feature.key(), caller.principalType());
            throw new BudgetExceededException(feature.key(), CALLER_SCOPE,
                    "Your daily AI allowance has been used; try again tomorrow");
        }
    }
}
