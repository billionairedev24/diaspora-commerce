package com.afrotransact.seller.port;

import com.afrotransact.seller.domain.LaunchListEntry;

/** Launch list for countries that are not live yet. */
public interface LaunchListRepository {

    /** Adds or updates (country, email); returns true when the entry is new. */
    boolean upsert(LaunchListEntry entry);
}
