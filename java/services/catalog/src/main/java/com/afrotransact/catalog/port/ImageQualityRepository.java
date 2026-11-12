package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.images.ImageQuality;
import com.afrotransact.catalog.domain.images.ImageQualityRecord;

/** Stored image quality of listings (table {@code product_image_quality}, S392). */
public interface ImageQualityRepository {

    /**
     * A listing waiting to be measured.
     *
     * @param productId       the listing
     * @param ownerOrg        its seller organisation
     * @param productTypeCode its product type (apparel may show a model when the rules allow it)
     * @param title           its title
     * @param atin            its ATIN
     * @param mainImageUrl    the first picture that is not the label photo, or null when it has none
     */
    record Candidate(UUID productId, String ownerOrg, String productTypeCode, String title, String atin,
                     String mainImageUrl) {
    }

    /**
     * Live and paused listings that were never measured or whose score is older than {@code staleBefore}, the never
     * measured first, then the oldest score. The next call after a score is saved returns the next ones, so the job
     * resumes by itself after a restart.
     */
    List<Candidate> candidates(Instant staleBefore, int limit);

    /** How many live and paused listings have never been measured. */
    long unscored();

    Optional<ImageQualityRecord> find(UUID productId);

    /** Inserts or replaces the stored score of a listing. */
    void save(ImageQualityRecord record);

    /** Drops the score of a listing whose main image is gone or that is no longer live (nothing to improve). */
    void remove(UUID productId);

    /** The listings of an organisation that need attention (IMPROVE or FAIL), failing ones first. */
    List<ImageQualityRecord> tasks(String ownerOrg);

    /** How many listings are in each state. */
    Map<ImageQuality, Long> counts();

    /** Failing listings whose grace period ended at or before {@code cutoff} and that are not hidden yet. */
    List<ImageQualityRecord> failingSince(Instant cutoff, int limit);

    /** How many listings {@link #failingSince} would list. */
    long countDue(Instant cutoff);

    /** Failing listings, oldest failure first (the staff report). */
    List<ImageQualityRecord> failing(int limit);

    /** Sets {@code hidden_at} on the listing. */
    void markHidden(UUID productId, Instant at);

    /** Which of the listings the hide step limited. */
    Set<UUID> hidden(Collection<UUID> productIds);

    /** A map with every state present. */
    static Map<ImageQuality, Long> emptyCounts() {
        Map<ImageQuality, Long> out = new EnumMap<>(ImageQuality.class);
        for (ImageQuality q : ImageQuality.values()) {
            out.put(q, 0L);
        }
        return out;
    }
}
