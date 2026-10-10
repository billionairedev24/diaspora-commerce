package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.careers.ApplicationDraft;
import com.afrotransact.content.domain.careers.DefaultApplicationPolicy;
import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;
import com.afrotransact.content.domain.careers.JobStatus;
import com.afrotransact.content.domain.careers.WorkAuthorization;
import com.afrotransact.content.domain.legal.ConsentPolicy;
import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.newsletter.NewsletterSubscription;
import com.afrotransact.content.domain.newsletter.SubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Email, newsletter, careers application and cookie consent rules. */
class VisitorFormsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final Job OPEN = new Job("go-engineer-remote-us", "Go Engineer", "Engineering", "Remote (US)",
            "Full-time", "$160k–$200k", "Search", List.of(), List.of(), JobStatus.OPEN, 0, NOW, NOW);

    @ParameterizedTest
    @ValueSource(strings = {"ada@example.com", "Ada.Nwosu+news@Mail.Example.co.uk", "a@b.io"})
    void validEmails(String email) {
        assertThat(EmailAddress.parse(email, "email").value()).isEqualTo(email.trim().toLowerCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ada", "ada@", "@example.com", "ada@example", "ada@@example.com", "ada @example.com",
        "ada@example.c", ".ada@example.com", "ada..b@example.com"})
    void invalidEmails(String email) {
        assertThatThrownBy(() -> EmailAddress.parse(email, "email"))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("email"));
    }

    @Test
    void newsletterDoubleOptIn() {
        NewsletterSubscription s = NewsletterSubscription.pending(UUID.randomUUID(),
                EmailAddress.parse("ada@example.com", "email"), "en", "footer", NOW);
        assertThat(s.needsConfirmation()).isTrue();
        NewsletterSubscription confirmed = s.confirm(NOW);
        assertThat(confirmed.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.confirm(NOW)).isSameAs(confirmed);
        assertThat(confirmed.resubscribe("fr", "checkout", NOW)).isSameAs(confirmed);
        NewsletterSubscription left = confirmed.unsubscribe(NOW);
        assertThat(left.status()).isEqualTo(SubscriptionStatus.UNSUBSCRIBED);
        assertThatThrownBy(() -> left.confirm(NOW)).isInstanceOf(ContentStateConflict.class);
        assertThat(left.resubscribe("fr", "app", NOW).status()).isEqualTo(SubscriptionStatus.PENDING);
    }

    @Test
    void validApplicationIsNormalised() {
        JobApplication a = new DefaultApplicationPolicy().validate(OPEN, new ApplicationDraft("  Ada   Nwosu ",
                "Ada@Example.com", "https://www.linkedin.com/in/ada-nwosu", "Yes, with sponsorship", "asset_12345",
                "ada-cv.pdf", "", true), NOW);
        assertThat(a.name()).isEqualTo("Ada Nwosu");
        assertThat(a.email().value()).isEqualTo("ada@example.com");
        assertThat(a.workAuthorization()).isEqualTo(WorkAuthorization.YES_WITH_SPONSORSHIP);
        assertThat(a.coverNote()).isNull();
        assertThat(a.reference()).isNull();
        assertThat(a.issued(UUID.randomUUID(), JobApplication.reference(100042)).reference()).isEqualTo("CAR-100042");
    }

    @Test
    void invalidApplicationListsEveryProblem() {
        assertThatThrownBy(() -> new DefaultApplicationPolicy().validate(OPEN, new ApplicationDraft("A", "nope",
                "https://linkedin.example.com/ada", "Maybe", null, "cv.exe", null, false), NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsOnlyKeys(
                        "name", "email", "linkedin", "workAuthorization", "resumeAssetId", "resumeFileName", "consent"));
        Job closed = new Job(OPEN.id(), OPEN.title(), OPEN.team(), OPEN.location(), OPEN.type(), OPEN.pay(),
                OPEN.summary(), List.of(), List.of(), JobStatus.CLOSED, 0, NOW, NOW);
        assertThatThrownBy(() -> new DefaultApplicationPolicy().validate(closed, new ApplicationDraft("Ada Nwosu",
                "ada@example.com", null, "Yes", "asset_12345", "cv.pdf", null, true), NOW))
                .isInstanceOf(ContentStateConflict.class);
    }

    @Test
    void consentForcesNecessaryOnAndHonoursGpc() {
        List<CookieCategory> cats = List.of(new CookieCategory("necessary", "N", "d", true, false, 0),
                new CookieCategory("analytics", "A", "d", false, false, 1),
                new CookieCategory("advertising", "Ad", "d", false, true, 2));
        assertThat(ConsentPolicy.normalise(Map.of("necessary", false, "analytics", true, "advertising", true), cats, false))
                .containsExactlyInAnyOrderEntriesOf(Map.of("necessary", true, "analytics", true, "advertising", true));
        assertThat(ConsentPolicy.normalise(Map.of("analytics", true, "advertising", true), cats, true))
                .containsEntry("advertising", false).containsEntry("analytics", true).containsEntry("necessary", true);
        assertThat(ConsentPolicy.normalise(null, cats, false)).containsEntry("analytics", false);
        assertThatThrownBy(() -> ConsentPolicy.normalise(Map.of("tracking", true), cats, false))
                .isInstanceOf(ContentRuleViolation.class);
    }
}
