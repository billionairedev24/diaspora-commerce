package com.afrotransact.content.domain.legal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A legal document's current version and the rules for changing it.
 *
 * @param type    document
 * @param current current version
 */
public record LegalDocument(LegalDocumentType type, LegalVersion current) {

    /** Locales a body may be provided in (the locales the legal center offers). */
    public static final Set<String> LOCALES = Set.of("en", "fr", "es", "ar", "yo");

    /**
     * Raises the version: the new version becomes CURRENT and the previous one is archived (still retrievable).
     * The new number must be higher, the English body is required and the effective date may not precede the
     * current one.
     */
    public Raise raise(String id, NewVersion draft, String actor, Instant now) {
        SemanticVersion next = SemanticVersion.parse(draft.version());
        Map<String, String> errors = new LinkedHashMap<>();
        if (!next.newerThan(current.version())) {
            errors.put("version", "Must be higher than the current version " + current.version());
        }
        if (draft.effectiveDate() == null) {
            errors.put("effectiveDate", "Required");
        } else if (draft.effectiveDate().isBefore(current.effectiveDate())) {
            errors.put("effectiveDate", "Cannot be before the current version's effective date " + current.effectiveDate());
        }
        errors.putAll(bodyProblems(draft.bodies()));
        ContentRuleViolation.throwIfAny("content.invalid-legal-version", "The new version is invalid", errors);
        LegalVersion created = new LegalVersion(UUID.fromString(id), type, next, draft.effectiveDate(),
                LegalVersionStatus.CURRENT, draft.draftBanner(), draft.bodies(), draft.changeSummary(), actor, now, null);
        return new Raise(created, current.archive(now));
    }

    /** Changes the current version in place: counsel sign-off (banner off), effective date or translations. */
    public LegalVersion amend(Boolean draftBanner, LocalDate effectiveDate, Map<String, String> bodies) {
        Map<String, String> merged = new LinkedHashMap<>(current.bodies());
        if (bodies != null) {
            merged.putAll(bodies);
        }
        ContentRuleViolation.throwIfAny("content.invalid-legal-version", "The change is invalid", bodyProblems(merged));
        return current.amend(draftBanner == null ? current.draftBanner() : draftBanner,
                effectiveDate == null ? current.effectiveDate() : effectiveDate, merged);
    }

    private static Map<String, String> bodyProblems(Map<String, String> bodies) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (bodies == null || bodies.getOrDefault(LegalVersion.BINDING_LOCALE, "").isBlank()) {
            errors.put("bodies.en", "The English text is required: it is the binding version");
            return errors;
        }
        bodies.forEach((locale, text) -> {
            if (!LOCALES.contains(locale)) {
                errors.put("bodies." + locale, "Locale must be one of " + LOCALES);
            } else if (text == null || text.isBlank()) {
                errors.put("bodies." + locale, "Empty text; omit the locale instead");
            } else if (text.length() > 200_000) {
                errors.put("bodies." + locale, "At most 200,000 characters");
            }
        });
        return errors;
    }

    /**
     * Input for a new version.
     *
     * @param version       new version number
     * @param effectiveDate effective date
     * @param draftBanner   show the counsel-review banner
     * @param bodies        markdown per locale
     * @param changeSummary what changed
     */
    public record NewVersion(String version, LocalDate effectiveDate, boolean draftBanner, Map<String, String> bodies,
                             String changeSummary) {
    }

    /**
     * Result of raising a version.
     *
     * @param created  the new CURRENT version
     * @param archived the previous version, now ARCHIVED
     */
    public record Raise(LegalVersion created, LegalVersion archived) {
    }
}
