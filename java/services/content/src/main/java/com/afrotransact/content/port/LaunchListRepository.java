package com.afrotransact.content.port;

import com.afrotransact.content.domain.newsletter.LaunchListEntry;

/** Launch lists per country. */
public interface LaunchListRepository {

    /** Inserts or refreshes the entry for (email, country, audience); returns the stored entry. */
    LaunchListEntry upsert(LaunchListEntry entry);
}
