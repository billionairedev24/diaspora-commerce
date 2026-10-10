package com.afrotransact.content.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.content.domain.ads.Campaign;

/** JSON payloads of content events. */
public final class ContentEventPayloads {

    private ContentEventPayloads() {
    }

    /**
     * A sponsored-products campaign was paused: its budget is spent (BUDGET_EXHAUSTED) or its owner paused it
     * (MANUAL). Notifications tell the seller; search stops serving it within its campaign cache TTL.
     *
     * @param campaignId       campaign
     * @param sellerOrgId      advertiser organisation
     * @param name             campaign name
     * @param reason           BUDGET_EXHAUSTED or MANUAL
     * @param spentMinor       lifetime spend
     * @param totalBudgetMinor lifetime budget
     * @param currency         ISO 4217
     * @param occurredAt       when
     */
    public record CampaignPaused(String campaignId, String sellerOrgId, String name, String reason, long spentMinor,
                                 long totalBudgetMinor, String currency, Instant occurredAt) {
    }

    static CampaignPaused campaignPaused(Campaign c, Instant at) {
        return new CampaignPaused(c.id(), c.sellerOrgId(), c.content().name(),
                c.pauseReason() == null ? null : c.pauseReason().name(), c.spentMinor(), c.content().totalBudgetMinor(),
                c.content().currency(), at);
    }

    /**
     * Placements of a market changed: the edge purges the listed bundle paths and cache tags.
     *
     * @param placementId placement that changed
     * @param slot        slot key
     * @param market      market whose bundle changed ({@code *} when the placement targets every market)
     * @param status      new status
     * @param action      SCHEDULED, LIVE or ENDED
     * @param purgePaths  CDN paths to purge
     * @param cacheTags   CDN cache tags to purge
     * @param occurredAt  when
     */
    public record PlacementsPublished(String placementId, String slot, String market, String status, String action,
                                      List<String> purgePaths, List<String> cacheTags, Instant occurredAt) {
    }

    /**
     * A new markets version is live.
     *
     * @param version          version
     * @param publishedBy      who
     * @param note             note
     * @param changedCountries countries that changed
     * @param newlyLive        countries now LIVE (launch-list emails)
     * @param disabledRegions  regions where the marketplace switched off ({@code US-TX})
     * @param enabledRegions   regions where it switched on
     */
    public record MarketsPublished(int version, String publishedBy, String note, List<String> changedCountries,
                                   List<String> newlyLive, List<String> disabledRegions, List<String> enabledRegions) {
    }

    /**
     * Site configuration saved; web and mobile refresh.
     *
     * @param revision        new revision
     * @param changedSections sections that changed
     * @param updatedBy       who
     */
    public record SiteConfigChanged(int revision, List<String> changedSections, String updatedBy) {
    }

    /**
     * A legal document version was raised or amended.
     *
     * @param document        document key
     * @param title           title
     * @param version         version now current
     * @param previousVersion archived version (raise only)
     * @param effectiveDate   effective date
     * @param draftBanner     counsel-review banner shown
     * @param href            public page path
     */
    public record LegalVersionChanged(String document, String title, String version, String previousVersion,
                                      LocalDate effectiveDate, boolean draftBanner, String href) {
    }

    /**
     * A flag changed (OpenFeature providers may refresh early).
     *
     * @param key               flag
     * @param enabled           enabled
     * @param killSwitch        kill switch
     * @param rolloutPercentage rollout
     * @param version           version
     * @param updatedBy         who
     */
    public record FlagChanged(String key, boolean enabled, boolean killSwitch, double rolloutPercentage, long version,
                              String updatedBy) {
    }

    /**
     * Notifications sends the double opt-in email.
     *
     * @param subscriptionId subscription
     * @param email          recipient
     * @param locale         language
     * @param confirmUrl     link that confirms (signed)
     * @param unsubscribeUrl link that cancels (signed)
     * @param expiresAt      when the confirm link expires
     */
    public record NewsletterConfirmationRequested(UUID subscriptionId, String email, String locale, String confirmUrl,
                                                  String unsubscribeUrl, Instant expiresAt) {
    }

    /**
     * Subscription confirmed or cancelled.
     *
     * @param subscriptionId subscription
     * @param email          address
     * @param locale         language
     * @param unsubscribeUrl signed one-click unsubscribe link (List-Unsubscribe)
     */
    public record NewsletterStatusChanged(UUID subscriptionId, String email, String locale, String unsubscribeUrl) {
    }

    /**
     * Someone joined a country's launch list.
     *
     * @param entryId         entry
     * @param email           address
     * @param country         ISO country
     * @param countryName     name
     * @param audience        SELLER, PROVIDER or SHOPPER
     * @param shoppingUpdates also wants shopping updates
     * @param launchNote      the country's launch note
     */
    public record LaunchListJoined(UUID entryId, String email, String country, String countryName, String audience,
                                   boolean shoppingUpdates, String launchNote) {
    }

    /**
     * A careers application arrived (notifications acknowledges it; recruiting picks it up).
     *
     * @param reference      CAR-######
     * @param jobId          role
     * @param jobTitle       title
     * @param team           team
     * @param location       location
     * @param name           candidate
     * @param email          candidate email
     * @param resumeAssetId  media asset id
     * @param resumeFileName file name
     * @param submittedAt    when
     */
    public record CareerApplicationReceived(String reference, String jobId, String jobTitle, String team,
                                            String location, String name, String email, String resumeAssetId,
                                            String resumeFileName, Instant submittedAt) {
    }
}
