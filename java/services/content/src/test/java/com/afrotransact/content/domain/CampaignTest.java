package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.domain.ads.CampaignStatus;
import com.afrotransact.content.domain.ads.PauseReason;
import org.junit.jupiter.api.Test;

class CampaignTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    static CampaignContent content(long bid, Long daily, long total) {
        return new CampaignContent(" Egusi always-on ", "Mama Tee", "us", bid, daily, total, "usd",
                List.of("Egusi", " melon  seeds ", "egusi"), List.of("Groceries › Pantry"), List.of("AT1EGU0001"),
                NOW.minusSeconds(60), null);
    }

    @Test
    void normalisesAndValidates() {
        Campaign c = Campaign.create("cmp_1", "org_mama", content(40, 200L, 1000), "u1", NOW);
        assertThat(c.content().name()).isEqualTo("Egusi always-on");
        assertThat(c.content().market()).isEqualTo("US");
        assertThat(c.content().currency()).isEqualTo("USD");
        assertThat(c.content().keywords()).containsExactly("egusi", "melon seeds");
        assertThat(c.status()).isEqualTo(CampaignStatus.ACTIVE);

        assertThatThrownBy(() -> Campaign.create("cmp_2", "org", new CampaignContent("x", "", "USA", 5, 1L, 100, "usd",
                List.of(), List.of(), List.of("nope"), null, null), "u1", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors())
                        .containsKeys("name", "advertiser", "market", "bidMinor", "totalBudgetMinor", "keywords",
                                "atins", "startsAt"));
    }

    @Test
    void clicksAreChargedWithinBudgetsAndPauseAtZero() {
        Campaign c = Campaign.create("cmp_1", "org_mama", content(40, 60L, 1000), "u1", NOW);
        Campaign.Charge first = c.charge(35, 0, NOW);
        assertThat(first.chargedMinor()).isEqualTo(35);
        assertThat(first.campaign().spentMinor()).isEqualTo(35);
        // Daily cap: 60 per day, 35 spent → at most 25 more today.
        Campaign.Charge capped = first.campaign().charge(35, 35, NOW);
        assertThat(capped.chargedMinor()).isEqualTo(25);
        assertThat(capped.campaign().servable(NOW, 60)).isFalse();
        assertThat(capped.campaign().servable(NOW, 0)).isTrue();

        Campaign nearlySpent = new Campaign("cmp_1", "org_mama", c.content(), CampaignStatus.ACTIVE, null, 990, "u1",
                NOW, NOW, 3);
        Campaign.Charge last = nearlySpent.charge(35, 0, NOW);
        assertThat(last.chargedMinor()).isEqualTo(10);
        assertThat(last.justExhausted()).isTrue();
        assertThat(last.campaign().status()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(last.campaign().pauseReason()).isEqualTo(PauseReason.BUDGET_EXHAUSTED);
        assertThat(last.campaign().remainingMinor()).isZero();

        Campaign.Charge after = last.campaign().charge(35, 0, NOW);
        assertThat(after.chargedMinor()).isZero();
        assertThat(after.justExhausted()).isFalse();

        assertThatThrownBy(() -> last.campaign().resume(NOW)).isInstanceOf(ContentStateConflict.class);
        Campaign refilled = last.campaign().edit(content(40, 60L, 2000), NOW);
        assertThat(refilled.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(refilled.pauseReason()).isNull();
        assertThatThrownBy(() -> refilled.edit(content(40, 60L, 900), NOW))
                .isInstanceOf(ContentStateConflict.class);
    }

    @Test
    void manualPauseAndEnd() {
        Campaign c = Campaign.create("cmp_1", "org_mama", content(40, null, 1000), "u1", NOW);
        Campaign paused = c.pause(NOW);
        assertThat(paused.pauseReason()).isEqualTo(PauseReason.MANUAL);
        assertThat(paused.servable(NOW, 0)).isFalse();
        assertThat(paused.resume(NOW).status()).isEqualTo(CampaignStatus.ACTIVE);
        Campaign ended = paused.end(NOW);
        assertThatThrownBy(() -> ended.pause(NOW)).isInstanceOf(ContentStateConflict.class);
        assertThat(ended.charge(35, 0, NOW).justExhausted()).isFalse();
    }
}
