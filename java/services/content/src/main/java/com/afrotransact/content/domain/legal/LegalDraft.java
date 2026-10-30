package com.afrotransact.content.domain.legal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.legal.LegalDocument.NewVersion;

/**
 * The working copy of a legal document that staff edit before it is published. Saving it changes nothing shoppers
 * read; publishing it raises the version. One draft per document.
 *
 * @param type          document
 * @param version       version the draft will be published as
 * @param effectiveDate effective date
 * @param draftBanner   show the counsel-review banner when published
 * @param bodies        markdown per locale (always contains {@code en})
 * @param needsReview   locales whose translation a reviewer still has to check
 * @param changeSummary what changed, shown in the history
 * @param baseVersion   the version that was current when the draft was started (a newer one makes the draft stale)
 * @param rowVersion    optimistic-locking counter
 * @param createdBy     who started the draft
 * @param createdAt     when
 * @param updatedBy     who saved it last (the person who may not approve it)
 * @param updatedAt     when
 */
public record LegalDraft(LegalDocumentType type, SemanticVersion version, LocalDate effectiveDate, boolean draftBanner,
                         Map<String, String> bodies, Set<String> needsReview, String changeSummary,
                         SemanticVersion baseVersion, long rowVersion, String createdBy, Instant createdAt,
                         String updatedBy, Instant updatedAt) {

    /** Text staff leave where counsel still has to decide; a document with one left cannot be published as final. */
    public static final Pattern MARKER = Pattern.compile("\\[\\[LEGAL:[^\\]]*]]");

    public LegalDraft {
        bodies = new TreeMap<>(bodies);
        needsReview = new TreeSet<>(needsReview);
    }

    /**
     * What staff change in one save.
     *
     * @param version       version number to publish as
     * @param effectiveDate effective date
     * @param draftBanner   show the counsel-review banner
     * @param bodies        markdown per locale
     * @param needsReview   translations still to be reviewed
     * @param changeSummary what changed
     */
    public record Edit(String version, LocalDate effectiveDate, boolean draftBanner, Map<String, String> bodies,
                       Set<String> needsReview, String changeSummary) {
    }

    /** A new draft that starts as a copy of the current version, numbered one minor version higher. */
    public static LegalDraft startFrom(LegalVersion current, String actor, Instant now) {
        SemanticVersion base = current.version();
        SemanticVersion next = new SemanticVersion(base.major(), base.minor() + 1, 0);
        return new LegalDraft(current.type(), next, current.effectiveDate(), current.draftBanner(), current.bodies(),
                Set.of(), null, base, 0, actor, now, actor, now);
    }

    /** A new draft that restores an older version's text (the version number is still a new, higher one). */
    public static LegalDraft restoring(LegalVersion current, LegalVersion old, String actor, Instant now) {
        LegalDraft started = startFrom(current, actor, now);
        return new LegalDraft(started.type, started.version, started.effectiveDate, old.draftBanner(), old.bodies(),
                Set.of(), "Restores version " + old.version(), started.baseVersion, 0, actor, now, actor, now);
    }

    /** The draft after a save: validated, with the editor recorded and the row version raised. */
    public LegalDraft edited(Edit edit, String actor, Instant now) {
        SemanticVersion next = SemanticVersion.parse(edit.version());
        Map<String, String> errors = new LinkedHashMap<>(LegalDocument.bodyProblems(edit.bodies()));
        if (edit.effectiveDate() == null) {
            errors.put("effectiveDate", "Required");
        }
        if (!next.newerThan(baseVersion)) {
            errors.put("version", "Must be higher than the current version " + baseVersion);
        }
        Set<String> review = edit.needsReview() == null ? Set.of() : edit.needsReview();
        for (String locale : review) {
            if (!LegalDocument.LOCALES.contains(locale) || LegalVersion.BINDING_LOCALE.equals(locale)) {
                errors.put("needsReview", "Only translations (fr, es, ar, yo) are marked for review");
            }
        }
        if (edit.changeSummary() != null && edit.changeSummary().length() > 1000) {
            errors.put("changeSummary", "At most 1,000 characters");
        }
        ContentRuleViolation.throwIfAny("content.invalid-legal-draft", "The draft is invalid", errors);
        String summary = edit.changeSummary() == null || edit.changeSummary().isBlank() ? null : edit.changeSummary().strip();
        return new LegalDraft(type, next, edit.effectiveDate(), edit.draftBanner(), edit.bodies(), review, summary,
                baseVersion, rowVersion + 1, createdBy, createdAt, actor, now);
    }

    /** How many {@code [[LEGAL: …]]} markers each locale still holds. */
    public Map<String, Integer> unresolvedMarkers() {
        Map<String, Integer> out = new TreeMap<>();
        bodies.forEach((locale, text) -> {
            Matcher m = MARKER.matcher(text);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                out.put(locale, n);
            }
        });
        return out;
    }

    /** Problems that stop publication (a final document may not hold counsel markers; the change is described). */
    public Map<String, String> publicationProblems() {
        Map<String, String> errors = new LinkedHashMap<>();
        int markers = unresolvedMarkers().values().stream().mapToInt(Integer::intValue).sum();
        if (!draftBanner && markers > 0) {
            errors.put("bodies", markers + " [[LEGAL: …]] marker" + (markers == 1 ? "" : "s")
                    + " left. Resolve them, or publish with the draft banner on.");
        }
        if (changeSummary == null) {
            errors.put("changeSummary", "Say what changed");
        }
        return errors;
    }

    /** True when {@code actor} saved the draft last: that person may not be the one who approves it. */
    public boolean editedBy(String actor) {
        return updatedBy.equals(actor);
    }

    public NewVersion toNewVersion() {
        return new NewVersion(version.toString(), effectiveDate, draftBanner, bodies, changeSummary);
    }

    /** The locales this draft holds text for. */
    public List<String> locales() {
        return List.copyOf(bodies.keySet());
    }
}
