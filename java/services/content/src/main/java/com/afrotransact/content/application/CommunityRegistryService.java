package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.home.CommunityEntry;

/** The community registry (S420): canonical keys, five-locale labels, aliases, optional editorial image. */
public interface CommunityRegistryService {

    /** Public read. */
    List<CommunityEntry> list();

    /** Creates or replaces an entry (content:home.edit); reason mandatory, audit-logged. */
    CommunityEntry put(String key, CommunityEntry entry, String reason);

    /** Removes an entry (content:home.edit); the community then uses its raw value. */
    void delete(String key, String reason);
}
