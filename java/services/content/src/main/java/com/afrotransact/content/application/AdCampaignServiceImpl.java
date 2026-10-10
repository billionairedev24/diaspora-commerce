package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.domain.ads.CampaignDay;
import com.afrotransact.content.domain.ads.CampaignStatus;
import com.afrotransact.content.port.AdCampaignRepository;
import com.afrotransact.content.port.AdStatsRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdCampaignServiceImpl implements AdCampaignService {

    private static final String ENTITY = "ad_campaign";
    private static final int MAX_REPORT_DAYS = 366;

    private final AdCampaignRepository campaigns;
    private final AdStatsRepository stats;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final Clock clock;

    AdCampaignServiceImpl(AdCampaignRepository campaigns, AdStatsRepository stats, ContentAccess access,
                          AuditTrail audit, DomainEventPublisher events, Clock clock) {
        this.campaigns = campaigns;
        this.stats = stats;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Campaign> list(String sellerOrgId, CampaignStatus status) {
        Actor actor = actor();
        if (isStaff(actor) && (actor.can(ContentPermissions.ADS_VIEW) || actor.can(ContentPermissions.ADS_EDIT))) {
            return campaigns.find(sellerOrgId, status, 500);
        }
        String own = actor.orgId().filter(o -> actor.can(ContentPermissions.ADS_MANAGE)).orElseThrow(this::forbidden);
        if (sellerOrgId != null && !sellerOrgId.equals(own)) {
            throw forbidden();
        }
        return campaigns.find(own, status, 500);
    }

    @Override
    @Transactional(readOnly = true)
    public Campaign get(String id) {
        Campaign c = load(id);
        requireRead(c.sellerOrgId());
        return c;
    }

    @Override
    @Transactional
    public Campaign create(String sellerOrgId, CampaignContent content) {
        if (sellerOrgId == null || sellerOrgId.isBlank()) {
            throw new ContentRuleViolation("content.invalid-campaign", "The campaign is not valid",
                    Map.of("sellerOrgId", "required"));
        }
        Actor actor = requireWrite(sellerOrgId);
        String id = "cmp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Campaign c = Campaign.create(id, sellerOrgId, content, actor.subject(), now());
        campaigns.insert(c);
        audit.record(actor.subject(), "ad_campaign.created", ENTITY, id, Map.of("sellerOrgId", sellerOrgId,
                "bidMinor", c.content().bidMinor(), "totalBudgetMinor", c.content().totalBudgetMinor()));
        return load(id);
    }

    @Override
    @Transactional
    public Campaign update(String id, CampaignContent content, long expectedVersion) {
        Campaign current = load(id);
        Actor actor = requireWrite(current.sellerOrgId());
        if (current.version() != expectedVersion) {
            throw stale(id);
        }
        Campaign next = current.edit(content, now());
        save(next);
        audit.record(actor.subject(), "ad_campaign.updated", ENTITY, id, Map.of("bidMinor", next.content().bidMinor(),
                "totalBudgetMinor", next.content().totalBudgetMinor(), "status", next.status().name()));
        return load(id);
    }

    @Override
    @Transactional
    public Campaign pause(String id) {
        return transition(id, "ad_campaign.paused", c -> c.pause(now()), true);
    }

    @Override
    @Transactional
    public Campaign resume(String id) {
        return transition(id, "ad_campaign.resumed", c -> c.resume(now()), false);
    }

    @Override
    @Transactional
    public Campaign end(String id) {
        return transition(id, "ad_campaign.ended", c -> c.end(now()), false);
    }

    private Campaign transition(String id, String action, Function<Campaign, Campaign> change, boolean pausing) {
        Campaign current = load(id);
        Actor actor = requireWrite(current.sellerOrgId());
        Campaign next = change.apply(current);
        if (next == current) {
            return current;
        }
        save(next);
        audit.record(actor.subject(), action, ENTITY, id, Map.of("status", next.status().name()));
        if (pausing) {
            events.publish(ContentEvents.ADS_CAMPAIGN_PAUSED, ContentEvents.AGGREGATE, id,
                    ContentEventPayloads.campaignPaused(next, now()));
        }
        return load(id);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignReport report(String id, LocalDate from, LocalDate to) {
        Campaign c = load(id);
        requireRead(c.sellerOrgId());
        LocalDate last = to == null ? LocalDate.ofInstant(now(), ZoneOffset.UTC) : to;
        LocalDate first = from == null ? last.minusDays(29) : from;
        if (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) >= MAX_REPORT_DAYS) {
            throw new ContentRuleViolation("content.invalid-range", "from must be before to, at most 366 days",
                    Map.of("from", "before to, at most 366 days"));
        }
        Map<LocalDate, CampaignDay> byDay = new HashMap<>();
        for (CampaignDay d : stats.days(id, first, last)) {
            byDay.put(d.day(), d);
        }
        List<CampaignDay> days = new ArrayList<>();
        CampaignDay total = CampaignDay.empty(null);
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            CampaignDay row = byDay.getOrDefault(d, CampaignDay.empty(d));
            days.add(row);
            total = total.plus(row);
        }
        return new CampaignReport(id, c.content().currency(), first, last, days, total, c.spentMinor(),
                c.remainingMinor());
    }

    private void save(Campaign c) {
        if (!campaigns.update(c)) {
            throw stale(c.id());
        }
    }

    private Campaign load(String id) {
        return campaigns.findById(id)
                .orElseThrow(() -> new NotFoundException("content.campaign-not-found", "No campaign " + id));
    }

    private Actor actor() {
        return access.optional().orElseThrow(
                () -> new ForbiddenActionException("auth.unauthenticated", "No authenticated actor"));
    }

    /** Staff with view/edit, or a member of the organisation holding ads:manage. */
    private Actor requireRead(String sellerOrgId) {
        Actor actor = actor();
        if (isStaff(actor) && (actor.can(ContentPermissions.ADS_VIEW) || actor.can(ContentPermissions.ADS_EDIT))) {
            return actor;
        }
        return requireMember(actor, sellerOrgId);
    }

    /** Staff with edit, or a member of the organisation holding ads:manage. */
    private Actor requireWrite(String sellerOrgId) {
        Actor actor = actor();
        if (isStaff(actor) && actor.can(ContentPermissions.ADS_EDIT)) {
            return actor;
        }
        return requireMember(actor, sellerOrgId);
    }

    private Actor requireMember(Actor actor, String sellerOrgId) {
        if (actor.can(ContentPermissions.ADS_MANAGE) && actor.orgId().map(sellerOrgId::equals).orElse(false)) {
            return actor;
        }
        throw forbidden();
    }

    private ForbiddenActionException forbidden() {
        return new ForbiddenActionException("auth.missing-permission",
                "Only the organisation's advertisers (ads:manage) or staff (content:ads.*) can do this");
    }

    private static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF;
    }

    private static ConflictException stale(String id) {
        return new ConflictException("content.concurrent-change", "Campaign " + id + " was changed by someone else; reload it");
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
