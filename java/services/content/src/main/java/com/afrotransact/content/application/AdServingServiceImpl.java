package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.port.AdCampaignRepository;
import com.afrotransact.content.port.AdStatsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdServingServiceImpl implements AdServingService {

    private final AdCampaignRepository campaigns;
    private final AdStatsRepository stats;
    private final ContentAccess access;
    private final Clock clock;

    AdServingServiceImpl(AdCampaignRepository campaigns, AdStatsRepository stats, ContentAccess access, Clock clock) {
        this.campaigns = campaigns;
        this.stats = stats;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServableCampaign> eligible(String market) {
        Actor actor = access.optional().orElseThrow(
                () -> new ForbiddenActionException("auth.unauthenticated", "No authenticated actor"));
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(ContentPermissions.ADS_SERVE);
        boolean staff = actor.type() == PrincipalType.STAFF
                && (actor.can(ContentPermissions.ADS_VIEW) || actor.can(ContentPermissions.ADS_EDIT));
        if (!service && !staff) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + ContentPermissions.ADS_SERVE);
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Campaign> active = campaigns.findActive(market == null ? "US" : market.trim().toUpperCase(Locale.ROOT));
        Map<String, Long> spentToday = stats.spendOn(active.stream().map(Campaign::id).toList(), today);
        return active.stream()
                .filter(c -> c.servable(now, spentToday.getOrDefault(c.id(), 0L)))
                .map(c -> new ServableCampaign(c.id(), c.sellerOrgId(), c.content().advertiser(), c.content().bidMinor(),
                        c.servableBudget(spentToday.getOrDefault(c.id(), 0L)), c.content().currency(),
                        c.content().keywords(), c.content().categories(), c.content().atins()))
                .toList();
    }
}
