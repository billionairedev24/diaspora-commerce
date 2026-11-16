package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.afrotransact.ai.domain.SpendLimits;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;

@Service
class SpendTodayServiceImpl implements SpendTodayService {

    private final UsageLedger ledger;
    private final SpendLimits limits;
    private final AiSpendCapPolicy cap;
    private final Clock clock;

    SpendTodayServiceImpl(UsageLedger ledger, SpendLimits limits, AiSpendCapPolicy cap, Clock clock) {
        this.ledger = ledger;
        this.limits = limits;
        this.cap = cap;
        this.clock = clock;
    }

    @Override
    public SpendToday today(Caller caller) {
        if (!"STAFF".equals(caller.principalType()) || !caller.can(AiPermissions.MANAGE)) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + AiPermissions.MANAGE);
        }
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<FeatureSpend> byFeature = ledger.dayUsageByFeature(day).stream()
                .map(u -> new FeatureSpend(u.feature(), u.calls(), u.tokens(), u.costMicros())).toList();
        long spent = byFeature.stream().mapToLong(FeatureSpend::costMicros).sum();
        long calls = byFeature.stream().mapToLong(FeatureSpend::calls).sum();
        long tokens = byFeature.stream().mapToLong(FeatureSpend::tokens).sum();
        return new SpendToday(day, cap.capMicros(), limits.callerDailyCostMicros(), spent, calls, tokens,
                calls > 0, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), byFeature);
    }
}
