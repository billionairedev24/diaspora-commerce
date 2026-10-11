package com.afrotransact.content.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.MarketSnapshot;
import com.afrotransact.content.domain.market.Region;

/** Markets & locations: the editable draft and immutable published versions. */
public interface MarketRepository {

    /** Draft countries with their regions, launch markets first then by name. */
    List<Country> draftCountries();

    Optional<Country> draftCountry(String code);

    /**
     * Saves a country's status and launch note (regions untouched) and bumps its version. Returns false when the
     * stored version is no longer {@code country.version()} (a concurrent edit won).
     */
    boolean saveDraftCountry(Country country, String actor, Instant now);

    /**
     * Saves a region's switches, status, city coverage and cities, and bumps its version. Returns false when the
     * stored version is no longer {@code region.version()} (a concurrent edit won).
     */
    boolean saveDraftRegion(Region region, String actor, Instant now);

    /** Adds a region that is not in the draft yet (a state picked from the gazetteer). */
    void insertDraftRegion(Region region, String actor, Instant now);

    /** Latest published version number. */
    int latestVersion();

    /** The published snapshot of {@code version}. */
    MarketSnapshot snapshot(int version);

    /**
     * Copies the current draft into published version {@code version}. Returns false when that version already
     * exists (a concurrent publish won).
     */
    boolean publish(int version, String actor, String note, Instant now);

    /** Published versions, newest first. */
    List<Publication> publications(int limit);

    /**
     * A published version.
     *
     * @param version     number
     * @param publishedAt when
     * @param publishedBy who
     * @param note        note
     */
    record Publication(int version, Instant publishedAt, String publishedBy, String note) {
    }
}
