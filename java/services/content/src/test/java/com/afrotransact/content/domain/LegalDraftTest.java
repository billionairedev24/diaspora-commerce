package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalDraft;
import com.afrotransact.content.domain.legal.LegalDraft.Edit;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersionStatus;
import com.afrotransact.content.domain.legal.SemanticVersion;
import org.junit.jupiter.api.Test;

class LegalDraftTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final LegalVersion CURRENT = new LegalVersion(UUID.randomUUID(), LegalDocumentType.RETURNS,
            SemanticVersion.parse("2.0"), LocalDate.parse("2026-06-01"), LegalVersionStatus.CURRENT, false,
            Map.of("en", "# Returns\n\n## Window\n\n30 days.", "fr", "# Retours"), "Launch", "counsel", NOW, null);
    private static final LegalVersion OLD = new LegalVersion(UUID.randomUUID(), LegalDocumentType.RETURNS,
            SemanticVersion.parse("1.0"), LocalDate.parse("2026-01-01"), LegalVersionStatus.ARCHIVED, true,
            Map.of("en", "# Returns\n\n## Window\n\n14 days."), "First", "counsel", NOW, NOW);

    private static Edit edit(String version, String en, String summary) {
        return new Edit(version, LocalDate.parse("2026-11-01"), true, Map.of("en", en), Set.of(), summary);
    }

    @Test
    void aDraftStartsAsACopyOfTheCurrentVersionOneMinorHigher() {
        LegalDraft d = LegalDraft.startFrom(CURRENT, "maya", NOW);
        assertThat(d.version()).hasToString("2.1");
        assertThat(d.baseVersion()).hasToString("2.0");
        assertThat(d.bodies()).isEqualTo(CURRENT.bodies());
        assertThat(d.editedBy("maya")).isTrue();
        assertThat(d.editedBy("sam")).isFalse();
    }

    @Test
    void savingRecordsTheEditorAndRaisesTheRowVersion() {
        LegalDraft d = LegalDraft.startFrom(CURRENT, "maya", NOW).edited(edit("2.1", "# Returns\n\n## Window\n\n45 days.", " Longer "), "sam", NOW);
        assertThat(d.updatedBy()).isEqualTo("sam");
        assertThat(d.createdBy()).isEqualTo("maya");
        assertThat(d.rowVersion()).isEqualTo(1);
        assertThat(d.changeSummary()).isEqualTo("Longer");
        assertThat(d.editedBy("maya")).isFalse();
    }

    @Test
    void aDraftNeedsEnglishAHigherVersionAndOnlyTranslationsCanNeedReview() {
        LegalDraft d = LegalDraft.startFrom(CURRENT, "maya", NOW);
        assertThatThrownBy(() -> d.edited(edit("2.0", "x", null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("version"));
        assertThatThrownBy(() -> d.edited(new Edit("2.1", LocalDate.parse("2026-11-01"), true, Map.of("fr", "x"), Set.of(), null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("bodies.en"));
        assertThatThrownBy(() -> d.edited(new Edit("2.1", LocalDate.parse("2026-11-01"), true, Map.of("en", "x"), Set.of("en"), null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("needsReview"));
    }

    @Test
    void aFinalDocumentCannotKeepCounselMarkers() {
        LegalDraft withMarkers = LegalDraft.startFrom(CURRENT, "maya", NOW).edited(new Edit("2.1", LocalDate.parse("2026-11-01"), false,
                Map.of("en", "Reply within [[LEGAL: Q32 days]] and [[LEGAL: Q4]].", "fr", "[[LEGAL: Q1]]"), Set.of("fr"), "Adds terms"), "maya", NOW);
        assertThat(withMarkers.unresolvedMarkers()).containsEntry("en", 2).containsEntry("fr", 1);
        assertThat(withMarkers.publicationProblems()).containsKey("bodies");
        LegalDraft banner = LegalDraft.startFrom(CURRENT, "maya", NOW).edited(new Edit("2.1", LocalDate.parse("2026-11-01"), true,
                Map.of("en", "[[LEGAL: Q32]]"), Set.of(), "Adds terms"), "maya", NOW);
        assertThat(banner.publicationProblems()).isEmpty();
    }

    @Test
    void publishingNeedsAChangeSummary() {
        LegalDraft d = LegalDraft.startFrom(CURRENT, "maya", NOW).edited(edit("2.1", "x", null), "maya", NOW);
        assertThat(d.publicationProblems()).containsKey("changeSummary");
    }

    @Test
    void restoringAnOlderVersionKeepsItsTextUnderANewNumber() {
        LegalDraft d = LegalDraft.restoring(CURRENT, OLD, "maya", NOW);
        assertThat(d.version()).hasToString("2.1");
        assertThat(d.bodies()).isEqualTo(OLD.bodies());
        assertThat(d.draftBanner()).isTrue();
        assertThat(d.changeSummary()).isEqualTo("Restores version 1.0");
        assertThat(d.toNewVersion().version()).isEqualTo("2.1");
    }
}
