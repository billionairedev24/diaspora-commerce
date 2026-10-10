package com.afrotransact.content.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.port.AdStatsRepository;
import com.afrotransact.content.port.AdStatsRepository.OrgDaySpend;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdSpendServiceImpl implements AdSpendService {

    private final AdStatsRepository stats;
    private final ContentAccess access;

    AdSpendServiceImpl(AdStatsRepository stats, ContentAccess access) {
        this.stats = stats;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgDaySpend> spend(LocalDate from, LocalDate to) {
        Actor actor = access.optional().orElseThrow(
                () -> new ForbiddenActionException("auth.unauthenticated", "No authenticated actor"));
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(ContentPermissions.ADS_SPEND_READ);
        boolean staff = actor.type() == PrincipalType.STAFF
                && (actor.can(ContentPermissions.ADS_VIEW) || actor.can(ContentPermissions.ADS_EDIT));
        if (!service && !staff) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + ContentPermissions.ADS_SPEND_READ);
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new ContentRuleViolation("content.invalid-range", "from must be on or before to",
                    Map.of("to", "Must be on or after from"));
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ContentRuleViolation("content.invalid-range", "At most " + MAX_DAYS + " days per request",
                    Map.of("to", "At most " + MAX_DAYS + " days after from"));
        }
        return stats.spendByOrg(from, to);
    }
}
