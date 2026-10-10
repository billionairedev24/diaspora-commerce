package com.afrotransact.content.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.site.SiteConfig;

/** Append-only site configuration revisions. */
public interface SiteConfigRepository {

    Optional<SiteConfig> latest();

    /** Inserts a new revision; returns false when the revision number is already taken. */
    boolean insert(SiteConfig config, String summary);

    List<RevisionSummary> revisions(int limit);

    /**
     * One revision in the history list.
     *
     * @param revision  number
     * @param createdBy who
     * @param createdAt when
     * @param summary   change summary
     */
    record RevisionSummary(int revision, String createdBy, Instant createdAt, String summary) {
    }
}
