package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.home.CommunityEntry;

/** The community registry. */
public interface CommunityRepository {

    List<CommunityEntry> findAll();

    Optional<CommunityEntry> find(String key);

    void upsert(CommunityEntry entry, String updatedBy);

    boolean delete(String key);
}
