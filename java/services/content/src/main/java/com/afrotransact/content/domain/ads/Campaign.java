package com.afrotransact.content.domain.ads;

import java.time.Instant;

import com.afrotransact.content.domain.ContentStateConflict;

/**
 * A sponsored-products campaign of one seller organisation.
 *
 * @param id            {@code cmp_…}
 * @param sellerOrgId   advertiser organisation (must hold the buy box of the advertised products)
 * @param content       bids, budgets, targeting and schedule
 * @param status        ACTIVE, PAUSED (with a reason) or ENDED
 * @param pauseReason   set while PAUSED
 * @param spentMinor    charged clicks so far (never above the total budget)
 * @param createdBy     subject who created it
 * @param createdAt     creation time
 * @param updatedAt     last change
 * @param version       optimistic lock
 */
public record Campaign(String id, String sellerOrgId, CampaignContent content, CampaignStatus status,
                       PauseReason pauseReason, long spentMinor, String createdBy, Instant createdAt,
                       Instant updatedAt, long version) {

    public static Campaign create(String id, String sellerOrgId, CampaignContent content, String createdBy,
                                  Instant now) {
        return new Campaign(id, sellerOrgId, CampaignRules.normalise(content), CampaignStatus.ACTIVE, null, 0,
                createdBy, now, now, 0);
    }

    /** Budget left over the campaign's lifetime. */
    public long remainingMinor() {
        return Math.max(0, content.totalBudgetMinor() - spentMinor);
    }

    /** Edits bids, budgets, targeting or schedule; raising the budget of a campaign paused at zero resumes it. */
    public Campaign edit(CampaignContent next, Instant now) {
        requireNotEnded();
        CampaignContent normalised = CampaignRules.normalise(next);
        if (normalised.totalBudgetMinor() < spentMinor) {
            throw new ContentStateConflict("content.ads-budget-below-spend",
                    "The total budget cannot be lower than what is already spent (" + spentMinor + ")");
        }
        boolean refilled = pauseReason == PauseReason.BUDGET_EXHAUSTED && normalised.totalBudgetMinor() > spentMinor;
        CampaignStatus nextStatus = refilled ? CampaignStatus.ACTIVE : status;
        PauseReason nextReason = refilled ? null : pauseReason;
        return new Campaign(id, sellerOrgId, normalised, nextStatus, nextReason, spentMinor, createdBy, createdAt, now,
                version);
    }

    public Campaign pause(Instant now) {
        requireNotEnded();
        if (status == CampaignStatus.PAUSED) {
            return this;
        }
        return withStatus(CampaignStatus.PAUSED, PauseReason.MANUAL, spentMinor, now);
    }

    public Campaign resume(Instant now) {
        requireNotEnded();
        if (remainingMinor() <= 0) {
            throw new ContentStateConflict("content.ads-budget-exhausted",
                    "The budget is spent: raise the total budget to resume");
        }
        return withStatus(CampaignStatus.ACTIVE, null, spentMinor, now);
    }

    public Campaign end(Instant now) {
        if (status == CampaignStatus.ENDED) {
            return this;
        }
        return withStatus(CampaignStatus.ENDED, null, spentMinor, now);
    }

    /**
     * Charges a click: at most the price, what is left of the total budget and what is left of today's budget. A
     * campaign whose total budget reaches zero is paused (BUDGET_EXHAUSTED).
     *
     * @param priceMinor   second price of the click
     * @param spentToday   charged today before this click
     */
    public Charge charge(long priceMinor, long spentToday, Instant now) {
        long dailyLeft = content.dailyBudgetMinor() == null ? Long.MAX_VALUE
                : Math.max(0, content.dailyBudgetMinor() - spentToday);
        long charged = Math.max(0, Math.min(priceMinor, Math.min(remainingMinor(), dailyLeft)));
        long spent = spentMinor + charged;
        boolean exhausted = spent >= content.totalBudgetMinor() && status != CampaignStatus.ENDED;
        Campaign next = exhausted
                ? withStatus(CampaignStatus.PAUSED, PauseReason.BUDGET_EXHAUSTED, spent, now)
                : withStatus(status, pauseReason, spent, now);
        return new Charge(next, charged, exhausted && pauseReason != PauseReason.BUDGET_EXHAUSTED);
    }

    /** Whether the campaign may serve at {@code now} with {@code spentToday} already charged today. */
    public boolean servable(Instant now, long spentToday) {
        return status == CampaignStatus.ACTIVE && !now.isBefore(content.startsAt())
                && (content.endsAt() == null || now.isBefore(content.endsAt())) && servableBudget(spentToday) > 0;
    }

    /** Budget the campaign can still spend now: the smaller of lifetime and today's remaining budget. */
    public long servableBudget(long spentToday) {
        long daily = content.dailyBudgetMinor() == null ? Long.MAX_VALUE
                : Math.max(0, content.dailyBudgetMinor() - spentToday);
        return Math.min(remainingMinor(), daily);
    }

    private Campaign withStatus(CampaignStatus s, PauseReason reason, long spent, Instant now) {
        return new Campaign(id, sellerOrgId, content, s, reason, spent, createdBy, createdAt, now, version);
    }

    private void requireNotEnded() {
        if (status == CampaignStatus.ENDED) {
            throw new ContentStateConflict("content.ads-campaign-ended", "The campaign has ended");
        }
    }

    /**
     * Result of charging a click.
     *
     * @param campaign      the campaign after the charge
     * @param chargedMinor  amount charged (0 when no budget was left)
     * @param justExhausted the charge used up the budget and paused the campaign
     */
    public record Charge(Campaign campaign, long chargedMinor, boolean justExhausted) {
    }
}
