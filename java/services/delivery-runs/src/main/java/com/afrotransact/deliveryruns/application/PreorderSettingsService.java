package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.RegionCoverage;
import com.afrotransact.deliveryruns.port.PlaceDirectory.Place;

/**
 * Staff: platform pre-order settings (master switch, fallback, placements, payment setup) and coverage areas (S271).
 * Reads need {@code delivery-runs:read}; writes {@code preorders:manage}.
 */
public interface PreorderSettingsService {

    PreorderSettings get();

    /** Optimistic: {@code expectedVersion} from the ETag (null = any; 412 when stale). Publishes preorder.settings.changed. */
    PreorderSettings update(SettingsCommand command, Long expectedVersion);

    /** Drill-down: no country → countries; country → its regions; country + region → that region's areas. */
    AreaPage areas(String country, String region, String q, int page, int size);

    /** Adds a region or city area (switched on; 409 when it exists); returns the page of its region. */
    AreaPage addArea(String key);

    /**
     * Switches every area at or under each key on or off (a country code, a region key or one area); returns the
     * page the first key's row is on.
     */
    AreaPage toggle(List<String> keys, boolean on);

    /**
     * Removes an area (404 when unknown; 409 {@code delivery-runs.area-in-use} when a live pre-order sells there and
     * nothing else covers it); returns the page of its region.
     */
    AreaPage removeArea(String key);

    /** One region's coverage for the in-place editor (rows may be none: a region not covered yet). */
    RegionCoverageView region(String country, String region);

    /**
     * Replaces one region's coverage in place: adds and removes cities, adds or removes the "Every city" row and
     * switches each on or off, in one step. {@code expectedVersion} is the region's version the editor loaded (null =
     * any; 412 when anyone changed the region since). Removing a place an open, scheduled or closed pre-order sells
     * to is refused (409 {@code delivery-runs.area-in-use}); switching it off is allowed. Publishes
     * preorder.areas.changed with the actor.
     */
    RegionCoverageView updateRegion(RegionCoverage desired, Long expectedVersion);

    /** Search with {@code q} (2+ characters), else browse countries, a country's regions or a region's cities. */
    PlaceList places(String country, String region, String q);

    /** Seeds the design's demo coverage areas when missing (local profile only); returns areas added. */
    int seedDemoAreas();

    /**
     * @param items       places
     * @param regionLabel what the (given or US) country calls regions
     */
    record PlaceList(List<Place> items, String regionLabel) {
    }
}
