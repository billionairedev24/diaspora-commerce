package com.afrotransact.seller.application;

import java.util.Set;

import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.port.OrgDirectoryEntry;
import com.afrotransact.seller.port.Page;

/**
 * Paged organisation directory (id, display name, kind, status) for service clients such as the indexer, which warms
 * its seller names from it on start (S213). Callers need {@link #DIRECTORY_READ}: the {@code indexer-service}
 * client-credentials client, or staff.
 */
public interface OrgDirectoryService {

    /** Permission (client-credentials scope) to read the organisation directory. */
    String DIRECTORY_READ = "sellers:directory";

    /** Largest page served. */
    int MAX_PAGE_SIZE = 500;

    Page<OrgDirectoryEntry> directory(int page, int size, Set<OrgStatus> statuses);
}
