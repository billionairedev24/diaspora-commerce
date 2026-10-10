package com.afrotransact.content.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.legal.Heading;
import com.afrotransact.content.domain.legal.LegalDocument.NewVersion;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersion.LocalizedBody;
import com.afrotransact.content.domain.legal.LegalVersionStatus;

/** Legal &amp; privacy center: current documents, version history, raising and amending versions. */
public interface LegalService {

    List<LegalSummary> summaries();

    /** The current version rendered for {@code locale} (English is binding and the fallback). */
    LegalPage current(LegalDocumentType type, String locale);

    /** A specific (possibly archived) version. */
    LegalPage version(LegalDocumentType type, String version, String locale);

    /** Every version, newest first. */
    List<VersionRef> history(LegalDocumentType type);

    /** Raises the version; the previous one is archived. */
    LegalVersion raise(LegalDocumentType type, NewVersion draft);

    /** Edits the current version: counsel sign-off (banner off), effective date or translations. */
    LegalVersion amend(LegalDocumentType type, Boolean draftBanner, LocalDate effectiveDate, Map<String, String> bodies);

    /**
     * A rendered document page.
     *
     * @param version version metadata
     * @param body    rendered markdown with placeholders filled from the site config
     * @param outline "On this page" headings
     * @param history all versions
     * @param href    public page path
     */
    record LegalPage(LegalVersion version, LocalizedBody body, List<Heading> outline, List<VersionRef> history,
                     String href) {
    }

    /**
     * A version in the history list.
     *
     * @param version       version
     * @param effectiveDate effective date
     * @param status        CURRENT or ARCHIVED
     * @param archivedAt    when it was replaced
     * @param changeSummary what changed
     */
    record VersionRef(String version, LocalDate effectiveDate, LegalVersionStatus status, Instant archivedAt,
                      String changeSummary) {
    }
}
