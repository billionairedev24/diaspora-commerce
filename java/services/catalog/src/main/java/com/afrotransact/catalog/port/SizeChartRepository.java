package com.afrotransact.catalog.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.sizing.SizeChart;

/** Persistence port for sellers' size charts (optimistic locking on {@code version}). */
public interface SizeChartRepository {

    SizeChart insert(SizeChart chart, String createdBy);

    /**
     * Saves the chart's content and retirement if its version still matches; returns the stored copy with version + 1.
     * Throws a conflict when someone saved it in between.
     */
    SizeChart update(SizeChart chart);

    Optional<SizeChart> findById(UUID id);

    /** The owner's active (not retired) chart with this name, ignoring case. */
    Optional<SizeChart> findActiveByName(String ownerOrg, String name);

    /** The owner's active charts with these names, by lower-case name. */
    Map<String, SizeChart> activeByNames(String ownerOrg, Collection<String> names);

    ChartPage list(ChartQuery query);

    /**
     * @param ownerOrg        null = every organisation (staff)
     * @param productTypeCode only charts made for this type or for any type; null = all
     * @param categoryId      only charts made for this category node or for any; null = all
     * @param text            part of the chart name; null = all
     */
    record ChartQuery(String ownerOrg, String productTypeCode, UUID categoryId, boolean includeRetired, String text,
                      int page, int size) {
    }

    record ChartPage(List<SizeChart> items, long total, int page, int size) {

        public ChartPage {
            items = List.copyOf(items);
        }
    }
}
