package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import com.afrotransact.content.domain.placement.FrequencyCap;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Schedule;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.domain.placement.Sponsorship;
import com.afrotransact.content.domain.placement.Targeting;
import org.junit.jupiter.api.Test;

class PlacementWorkflowTest {

    @Test
    void draftReviewApproveGoesLiveWhenTheWindowHasStarted() {
        Placement p = draft(Placements.window(-1, 24), false);
        assertThat(p.status()).isEqualTo(PlacementStatus.DRAFT);
        Placement inReview = p.submit("maya", Placements.NOW);
        assertThat(inReview.status()).isEqualTo(PlacementStatus.IN_REVIEW);
        assertThat(inReview.submittedBy()).isEqualTo("maya");
        Placement live = inReview.approve("maya", Placements.NOW);
        assertThat(live.status()).isEqualTo(PlacementStatus.LIVE);
        assertThat(live.servableAt(Placements.NOW)).isTrue();
        Placement ended = live.end(Placements.NOW);
        assertThat(ended.status()).isEqualTo(PlacementStatus.ENDED);
        assertThat(ended.servableAt(Placements.NOW)).isFalse();
    }

    @Test
    void futureWindowIsScheduledThenActivatedThenEnded() {
        Placement scheduled = draft(Placements.window(2, 5), false).submit("maya", Placements.NOW)
                .approve("lead", Placements.NOW);
        assertThat(scheduled.status()).isEqualTo(PlacementStatus.SCHEDULED);
        assertThat(scheduled.dueForActivation(Placements.NOW)).isFalse();
        assertThatThrownBy(() -> scheduled.activate(Placements.NOW)).isInstanceOf(ContentStateConflict.class);
        Placement live = scheduled.activate(Placements.NOW.plusSeconds(3 * 3600));
        assertThat(live.status()).isEqualTo(PlacementStatus.LIVE);
        assertThat(live.dueForEnd(Placements.NOW.plusSeconds(6 * 3600))).isTrue();
    }

    @Test
    void paidCreativesNeedFourEyes() {
        Placement inReview = draft(Placements.window(-1, null), true).submit("maya", Placements.NOW);
        assertThatThrownBy(() -> inReview.approve("maya", Placements.NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("content.four-eyes-required"));
        Placement submittedByOther = draft(Placements.window(-1, null), true).submit("sam", Placements.NOW);
        assertThatThrownBy(() -> submittedByOther.approve("sam", Placements.NOW))
                .isInstanceOf(ContentRuleViolation.class);
        assertThatThrownBy(() -> submittedByOther.approve("maya", Placements.NOW))
                .isInstanceOf(ContentRuleViolation.class);
        assertThat(inReview.approve("lead", Placements.NOW).approvedBy()).isEqualTo("lead");
        assertThat(inReview.approve("lead", Placements.NOW).label()).isEqualTo("Sponsored");
    }

    @Test
    void firstPartyCreativesMayBeApprovedByTheirAuthor() {
        Placement p = draft(Placements.window(-1, null), false).submit("maya", Placements.NOW).approve("maya", Placements.NOW);
        assertThat(p.status()).isEqualTo(PlacementStatus.LIVE);
        assertThat(p.label()).isEqualTo("AfroTransact");
    }

    @Test
    void rejectionReturnsToDraftWithANoteAndOnlyDraftsAreEditable() {
        Placement inReview = draft(Placements.window(-1, null), false).submit("maya", Placements.NOW);
        assertThatThrownBy(() -> inReview.edit(Placements.content(Targeting.everyone(), Placements.window(-1, null),
                FrequencyCap.unlimited(), 1, false), Placements.NOW))
                .isInstanceOfSatisfying(ContentStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("content.placement-not-editable"));
        assertThatThrownBy(() -> inReview.reject("lead", " ", Placements.NOW)).isInstanceOf(ContentRuleViolation.class);
        Placement rejected = inReview.reject("lead", "Image too heavy", Placements.NOW);
        assertThat(rejected.status()).isEqualTo(PlacementStatus.DRAFT);
        assertThat(rejected.reviewNote()).isEqualTo("lead: Image too heavy");
        assertThat(rejected.edit(Placements.content(Targeting.everyone(), Placements.window(-1, null),
                FrequencyCap.unlimited(), 7, false), Placements.NOW).priority()).isEqualTo(7);
    }

    @Test
    void invalidTransitionsAreRejected() {
        Placement p = draft(Placements.window(-1, null), false);
        assertThatThrownBy(() -> p.approve("lead", Placements.NOW)).isInstanceOf(ContentStateConflict.class);
        assertThatThrownBy(() -> p.end(Placements.NOW)).isInstanceOf(ContentStateConflict.class);
        Placement expired = draft(Placements.window(-10, -1), false);
        assertThatThrownBy(() -> expired.submit("maya", Placements.NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("content.schedule-ended"));
    }

    @Test
    void contentValidation() {
        assertThatThrownBy(() -> new Schedule(Placements.NOW, Placements.NOW.minusSeconds(1)))
                .isInstanceOf(ContentRuleViolation.class);
        assertThatThrownBy(() -> new FrequencyCap(3, 0)).isInstanceOf(ContentRuleViolation.class);
        assertThatThrownBy(() -> Placement.draft("plc_x", SlotKey.HERO_CAROUSEL, Placements.content(
                new Targeting(Set.of("USA"), null, Set.of("77a"), null, null, null, null),
                Placements.window(0, null), FrequencyCap.unlimited(), 1, false), "maya", Placements.NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors())
                        .containsKeys("targeting.markets", "targeting.zipPrefixes"));
        assertThatThrownBy(() -> Placement.draft("plc_x", SlotKey.HERO_CAROUSEL,
                new Placement.PlacementContent("x", "{}", null, Placements.window(0, null), null, 0,
                        new Sponsorship(true, " ")), "maya", Placements.NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).isEqualTo(Map.of("sponsorship.advertiser",
                                "Paid creatives must name the advertiser")));
    }

    private static Placement draft(Schedule schedule, boolean paid) {
        return Placement.draft("plc_test", SlotKey.HERO_CAROUSEL,
                Placements.content(Targeting.everyone(), schedule, FrequencyCap.unlimited(), 1, paid), "maya",
                Placements.NOW);
    }
}
