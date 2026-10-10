package com.afrotransact.deliveryruns.port;

import java.util.Collection;
import java.util.List;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.CoverageArea;
import com.afrotransact.deliveryruns.domain.PreorderSettings;

/** The platform settings row and the coverage areas. */
public interface PreorderSettingsRepository {

    PreorderSettings settings();

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code settings.version()} is stale. */
    PreorderSettings update(PreorderSettings settings);

    /** Every coverage area (country, region, then city; region rows first). */
    List<CoverageArea> areas();

    /** @throws DuplicateEntryException when the key (city case-insensitive) exists */
    CoverageArea insertArea(AreaKey key, boolean on);

    /** Inserts the area when missing (local demo seed); returns true when inserted. */
    boolean insertAreaIfMissing(AreaKey key, boolean on);

    /** Switches the given keys on or off; returns how many rows changed. */
    int setOn(Collection<AreaKey> keys, boolean on);

    /** @return false when no such area */
    boolean deleteArea(AreaKey key);
}
