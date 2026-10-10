package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.legal.Heading;
import com.afrotransact.content.domain.legal.LegalDocument;
import com.afrotransact.content.domain.legal.LegalDocument.NewVersion;
import com.afrotransact.content.domain.legal.LegalDocument.Raise;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalTemplate;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersionStatus;
import com.afrotransact.content.domain.legal.SemanticVersion;
import org.junit.jupiter.api.Test;

class LegalDocumentTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final LegalVersion CURRENT = new LegalVersion(UUID.randomUUID(), LegalDocumentType.CONDITIONS_OF_USE,
            SemanticVersion.parse("3.2"), LocalDate.parse("2026-09-01"), LegalVersionStatus.CURRENT, true,
            Map.of("en", "# Conditions\n\n## Agreement\n\nText\n\n## Notifications, email and text messages\n\n"
                    + "SMS program: {smsProgramName}. Reply STOP to cancel, HELP for help, or call {phone}."),
            "First draft", "counsel", NOW, null);

    @Test
    void raisingAVersionArchivesThePreviousOne() {
        Raise r = new LegalDocument(LegalDocumentType.CONDITIONS_OF_USE, CURRENT).raise(UUID.randomUUID().toString(),
                new NewVersion("3.3", LocalDate.parse("2026-11-01"), false, Map.of("en", "# v3.3", "fr", "# v3.3 fr"),
                        "Adds SMS terms"), "maya", NOW);
        assertThat(r.created().status()).isEqualTo(LegalVersionStatus.CURRENT);
        assertThat(r.created().version()).hasToString("3.3");
        assertThat(r.created().draftBanner()).isFalse();
        assertThat(r.archived().id()).isEqualTo(CURRENT.id());
        assertThat(r.archived().status()).isEqualTo(LegalVersionStatus.ARCHIVED);
        assertThat(r.archived().archivedAt()).isEqualTo(NOW);
        assertThat(r.archived().bodies()).isEqualTo(CURRENT.bodies());
    }

    @Test
    void newVersionMustBeHigherDatedAndHaveEnglish() {
        LegalDocument doc = new LegalDocument(LegalDocumentType.CONDITIONS_OF_USE, CURRENT);
        assertThatThrownBy(() -> doc.raise(UUID.randomUUID().toString(), new NewVersion("3.2", LocalDate.parse("2026-11-01"),
                true, Map.of("en", "x"), null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("version"));
        assertThatThrownBy(() -> doc.raise(UUID.randomUUID().toString(), new NewVersion("4.0", LocalDate.parse("2026-01-01"),
                true, Map.of("fr", "x"), null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKeys("effectiveDate", "bodies.en"));
        assertThatThrownBy(() -> doc.raise(UUID.randomUUID().toString(), new NewVersion("4.0", LocalDate.parse("2026-12-01"),
                true, Map.of("en", "x", "de", "y"), null), "maya", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("bodies.de"));
        assertThatThrownBy(() -> SemanticVersion.parse("v3")).isInstanceOf(ContentRuleViolation.class);
    }

    @Test
    void semanticVersionsCompareNumerically() {
        assertThat(SemanticVersion.parse("3.10").newerThan(SemanticVersion.parse("3.9"))).isTrue();
        assertThat(SemanticVersion.parse("3.2.1").newerThan(SemanticVersion.parse("3.2"))).isTrue();
        assertThat(SemanticVersion.parse("3.2.0")).isEqualTo(SemanticVersion.parse("3.2"));
        assertThat(SemanticVersion.parse("3.2.1")).hasToString("3.2.1");
    }

    @Test
    void amendingKeepsTheVersionAndMergesTranslations() {
        LegalVersion amended = new LegalDocument(LegalDocumentType.CONDITIONS_OF_USE, CURRENT)
                .amend(false, null, Map.of("es", "# Condiciones"));
        assertThat(amended.version()).isEqualTo(CURRENT.version());
        assertThat(amended.draftBanner()).isFalse();
        assertThat(amended.bodies()).containsKeys("en", "es");
        assertThat(amended.effectiveDate()).isEqualTo(CURRENT.effectiveDate());
    }

    @Test
    void englishIsBindingAndTheFallback() {
        LegalVersion.LocalizedBody fr = CURRENT.body("fr");
        assertThat(fr.locale()).isEqualTo("en");
        assertThat(fr.fallback()).isTrue();
        assertThat(fr.binding()).isTrue();
        assertThat(CURRENT.body("en").fallback()).isFalse();
    }

    @Test
    void placeholdersAndOutline() {
        String rendered = LegalTemplate.render(CURRENT.bodies().get("en"),
                Map.of("smsProgramName", "AfroTransact Alerts", "phone", "+1 (833) 555-0199"));
        assertThat(rendered).contains("SMS program: AfroTransact Alerts.").contains("call +1 (833) 555-0199.")
                .contains("Reply STOP").contains("HELP");
        assertThat(LegalTemplate.render("Write to {legal} or {unknown}", Map.of("legal", "legal@afrotransact.com")))
                .isEqualTo("Write to legal@afrotransact.com or {unknown}");
        assertThat(Heading.outline(rendered)).extracting(Heading::id)
                .containsExactly("agreement", "notifications-email-and-text-messages");
        assertThat(Heading.outline("## Résumé\n## Résumé")).extracting(Heading::id).containsExactly("resume", "resume-2");
    }
}
