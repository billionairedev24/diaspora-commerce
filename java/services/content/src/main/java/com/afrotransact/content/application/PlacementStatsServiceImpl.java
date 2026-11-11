package com.afrotransact.content.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.port.PlacementStatsRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlacementStatsServiceImpl implements PlacementStatsService {

    private final PlacementStatsRepository stats;
    private final Clock clock;

    PlacementStatsServiceImpl(PlacementStatsRepository stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(String placementId, String kind) {
        long impressions = "impression".equals(kind) ? 1 : 0;
        long clicks = "click".equals(kind) ? 1 : 0;
        if (impressions + clicks == 0) {
            throw new ContentRuleViolation("content.invalid-event", "kind must be impression or click");
        }
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!stats.add(placementId, day, impressions, clicks)) {
            throw new NotFoundException("content.placement-not-found", "No placement " + placementId);
        }
    }
}
