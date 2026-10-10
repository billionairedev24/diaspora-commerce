package com.afrotransact.seller.port;

import java.util.Set;

import com.afrotransact.seller.domain.OrgStatus;

/** Read-only, paged view of every organisation for service clients (S213). */
public interface OrgDirectoryReader {

    /**
     * One page of organisations ordered by id (stable across pages).
     *
     * @param statuses only these statuses; empty means all
     */
    Page<OrgDirectoryEntry> page(int page, int size, Set<OrgStatus> statuses);
}
