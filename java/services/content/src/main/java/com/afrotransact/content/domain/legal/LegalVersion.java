package com.afrotransact.content.domain.legal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One version of a legal document. English is the binding text; other locales are courtesy translations.
 *
 * @param id            version id
 * @param type          document
 * @param version       semantic version
 * @param effectiveDate effective date
 * @param status        CURRENT or ARCHIVED
 * @param draftBanner   show "Draft for legal counsel review — not yet in force" until counsel approves
 * @param bodies        markdown per locale (always contains {@code en})
 * @param changeSummary what changed
 * @param createdBy     author
 * @param createdAt     created
 * @param archivedAt    when a newer version replaced it
 */
public record LegalVersion(UUID id, LegalDocumentType type, SemanticVersion version, LocalDate effectiveDate,
                           LegalVersionStatus status, boolean draftBanner, Map<String, String> bodies,
                           String changeSummary, String createdBy, Instant createdAt, Instant archivedAt) {

    /** Locale whose text is legally binding. */
    public static final String BINDING_LOCALE = "en";

    public LegalVersion {
        bodies = new TreeMap<>(bodies);
    }

    /** The body for {@code locale}, falling back to the binding English text. */
    public LocalizedBody body(String locale) {
        String wanted = locale == null ? BINDING_LOCALE : locale;
        String text = bodies.get(wanted);
        if (text == null) {
            return new LocalizedBody(BINDING_LOCALE, bodies.get(BINDING_LOCALE), true, !BINDING_LOCALE.equals(wanted));
        }
        return new LocalizedBody(wanted, text, BINDING_LOCALE.equals(wanted), false);
    }

    public LegalVersion archive(Instant now) {
        return new LegalVersion(id, type, version, effectiveDate, LegalVersionStatus.ARCHIVED, draftBanner, bodies,
                changeSummary, createdBy, createdAt, now);
    }

    public LegalVersion amend(boolean banner, LocalDate effective, Map<String, String> newBodies) {
        return new LegalVersion(id, type, version, effective, status, banner, newBodies, changeSummary, createdBy,
                createdAt, archivedAt);
    }

    /**
     * A body in one locale.
     *
     * @param locale   locale of the text returned
     * @param markdown the text
     * @param binding  the text is the binding (English) version
     * @param fallback the requested translation does not exist and English was returned
     */
    public record LocalizedBody(String locale, String markdown, boolean binding, boolean fallback) {
    }
}
