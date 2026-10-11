package com.afrotransact.content.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.City;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.LocationStatus;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.port.MarketRepository.Publication;

/**
 * Markets &amp; locations in the back office. Changes go to a draft; Publish makes them a new immutable version
 * that eligibility, checkout and onboarding read.
 */
public interface MarketService {

    List<Country> draftCountries();

    Country draftCountry(String code);

    /**
     * Changes a country's status or launch note. {@code expectedVersion} is the country version the editor loaded
     * (null = any); a newer stored version is refused with 412.
     */
    Country updateCountry(String code, MarketStatus status, String launchNote, Long expectedVersion);

    /** Changes switches of one region; marketplace off turns everything off there. */
    Country setCapabilities(String country, String region, Map<Capability, Boolean> changes);

    /**
     * Changes one region in place: its status (LIVE / PLANNED / OFF), whether it covers every city, and its
     * capability switches (only what is given). 412 when the region changed since {@code expectedVersion}.
     */
    Country updateRegion(String country, String region, RegionChange change, Long expectedVersion);

    /**
     * Adds a state / province to a country as PLANNED (picked from the gazetteer). A region that exists and is OFF
     * becomes PLANNED; one already in use is refused (409 content.region-in-use).
     */
    Country addRegion(String country, String code, String name);

    /** Appends cities to a region (existing ones keep their status). 412 on a stale {@code expectedVersion}. */
    Country addCities(String country, String region, List<City> cities, Long expectedVersion);

    /** Sets the status of listed cities. A LIVE city needs its region LIVE (422 content.region-not-live). */
    Country setCityStatus(String country, String region, List<String> names, LocationStatus status,
                          Long expectedVersion);

    /** Removes listed cities. */
    Country removeCities(String country, String region, List<String> names, Long expectedVersion);

    Country activateAll(String country);

    Country deactivateAll(String country);

    /** What Publish would change. */
    MarketChanges pendingChanges();

    PublishResult publish(String note);

    List<Publication> publications();

    /**
     * Outcome of a publish.
     *
     * @param version new version
     * @param changes what changed
     */
    record PublishResult(int version, MarketChanges changes) {
    }

    /**
     * A region edit; null fields stay as they are.
     *
     * @param status       LIVE, PLANNED or OFF
     * @param everyCity    cover every city (listed cities are overrides) or only the cities listed LIVE
     * @param capabilities switches to change
     */
    record RegionChange(LocationStatus status, Boolean everyCity, Map<Capability, Boolean> capabilities) {

        public RegionChange {
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        }
    }
}
