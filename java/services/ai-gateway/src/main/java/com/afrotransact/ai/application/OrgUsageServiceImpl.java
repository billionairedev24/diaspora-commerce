package com.afrotransact.ai.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.afrotransact.ai.domain.AiCredits;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;

@Service
class OrgUsageServiceImpl implements OrgUsageService {

    private final UsageLedger ledger;

    OrgUsageServiceImpl(UsageLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public List<OrgDayCredits> usage(Caller caller, LocalDate from, LocalDate to) {
        boolean service = "SERVICE".equals(caller.principalType()) && caller.can(AiPermissions.USAGE_READ);
        boolean staff = "STAFF".equals(caller.principalType()) && caller.can(AiPermissions.MANAGE);
        if (!service && !staff) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + AiPermissions.USAGE_READ);
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new AiRuleViolation("ai.invalid-range", "from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new AiRuleViolation("ai.invalid-range", "At most " + MAX_DAYS + " days per request");
        }
        return ledger.orgUsage(from, to).stream()
                .map(u -> new OrgDayCredits(u.orgId(), u.day(), u.calls(), u.tokens(), u.costMicros(),
                        AiCredits.fromCostMicros(u.costMicros())))
                .toList();
    }
}
