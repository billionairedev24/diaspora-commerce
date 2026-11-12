package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Existing and migrated listings against the enforced main-image rules (S392). Live listings are grandfathered: a
 * background job measures each listing's main image at media-service, stores OK, IMPROVE or FAIL, and the seller sees an
 * "Improve your image" task with the reasons in plain words. A separate flag ({@code at.catalog.images.hide-failing}, off
 * until the owner switches it on) lets the hide step limit listings that still fail after the grace period; nothing is
 * deleted and the seller can still edit.
 */
public interface ImageQualityService {

    /**
     * Measures the next batch of listings (never measured first, then the oldest score, then listings whose main picture
     * changed): one rescore call per run, so the rate is the batch size per run interval. Does nothing when the image
     * rules are off or media cannot be reached; the next run continues where this one stopped.
     *
     * @return how many listings were scored
     */
    int rescoreBatch();

    /** Staff (catalog:moderate) run one batch now, for example to see the first results after switching the rules on. */
    int rescoreNow();

    /** The caller's organisation's listings that need attention, failing ones first (the "Improve your image" task). */
    List<Task> myTasks();

    /** The staff report: counts per state, the reasons behind them, and what the hide step would do (dry run). */
    Report report();

    /**
     * The hide step for listings that still fail after the grace period (staff, catalog:moderate). With {@code dryRun} it
     * only counts. Without it nothing happens (and the result says so) unless the hide flag is on.
     */
    HideResult hideFailing(boolean dryRun);

    /** The hide step as the background job runs it (no actor): does nothing unless the hide flag is on. */
    HideResult hideDue();

    /** One finding in plain words; Seller Center translates by {@code code}. */
    record Reason(String code, String level, String message) {
    }

    /**
     * An "Improve your image" task.
     *
     * @param state        IMPROVE (passes with a warning) or FAIL (breaks a rule)
     * @param failingSince when it first failed; null for IMPROVE
     * @param hideAfter    when the grace period ends (the hide step may limit the listing after this, only when the hide
     *                     flag is on); null for IMPROVE
     * @param daysLeft     whole days left of the grace period; null for IMPROVE
     * @param hidden       whether the hide step has limited the listing
     */
    record Task(UUID productId, String atin, String title, String state, List<Reason> reasons, Instant failingSince,
                Instant hideAfter, Integer daysLeft, boolean hidden) {
    }

    /** A failing listing in the staff report. */
    record ReportRow(UUID productId, String atin, String ownerOrg, String title, List<String> reasonCodes,
                     Instant failingSince, Instant hideAfter, boolean pastGrace, boolean hidden) {
    }

    /**
     * The staff report.
     *
     * @param counts            listings per state (OK, IMPROVE, FAIL)
     * @param notScored         live and paused listings not measured yet
     * @param reasons           how many listings have each reason code
     * @param graceDays         the grace period
     * @param hideFlagOn        whether the hide step is switched on
     * @param wouldHide         failing listings past the grace period that the hide step would limit now
     * @param failing           the failing listings, oldest failure first (at most 200)
     */
    record Report(Map<String, Long> counts, long notScored, Map<String, Long> reasons, int graceDays,
                  boolean hideFlagOn, long wouldHide, List<ReportRow> failing) {
    }

    /**
     * @param dryRun   true when nothing was changed
     * @param flagOn   whether the hide flag is on
     * @param listings how many listings were (or would be) limited
     */
    record HideResult(boolean dryRun, boolean flagOn, int listings) {
    }
}
