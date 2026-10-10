package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.port.SiteConfigRepository.RevisionSummary;

/** Site content &amp; legal configuration: socials, contacts, app links, locales; versioned, audited, no deploys. */
public interface SiteConfigService {

    /** Public configuration with current legal versions and an ETag. */
    PublishedSiteConfig published();

    /** Full configuration for the back office (hidden socials included). */
    SiteConfig current();

    /**
     * Saves a new revision. {@code expectedRevision} must be the revision the editor loaded.
     *
     * @param draft   new content (revision, updatedAt and updatedBy are ignored)
     * @param summary optional change note
     */
    SiteConfig update(SiteConfig draft, int expectedRevision, String summary);

    List<RevisionSummary> revisions();

    /**
     * What web and mobile read.
     *
     * @param config configuration
     * @param legal  current legal documents
     * @param etag   fingerprint of revision and legal versions
     */
    record PublishedSiteConfig(SiteConfig config, List<LegalSummary> legal, String etag) {
    }
}
