package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequest.Submission;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;
import com.afrotransact.content.domain.privacy.PrivacyRequestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Your Privacy Choices request rules: validation, reference, 45-day deadline and resolution. */
class PrivacyRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static PrivacyRequest received() {
        return PrivacyRequest.submit(UUID.randomUUID(), 100001, new Submission("delete", " Ada@Example.com ", "tx",
                "  Close my account  ", "fr"), "user-123", NOW);
    }

    @Test
    void aValidSubmissionIsReceivedWithAReferenceAndA45DayDeadline() {
        PrivacyRequest r = received();
        assertThat(r.reference()).isEqualTo("PR-100001");
        assertThat(r.type()).isEqualTo(PrivacyRequestType.DELETE);
        assertThat(r.email().value()).isEqualTo("ada@example.com");
        assertThat(r.stateCode()).isEqualTo("TX");
        assertThat(r.details()).isEqualTo("Close my account");
        assertThat(r.locale()).isEqualTo("fr");
        assertThat(r.accountSubject()).contains("user-123");
        assertThat(r.status()).isEqualTo(PrivacyRequestStatus.RECEIVED);
        assertThat(r.dueAt()).isEqualTo(Instant.parse("2026-11-25T12:00:00Z"));
        assertThat(r.overdue(NOW)).isFalse();
        assertThat(r.overdue(Instant.parse("2026-11-26T00:00:00Z"))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"access,ACCESS", "OPTOUT,OPT_OUT", "opt_out,OPT_OUT", "correct,CORRECT"})
    void typesAcceptTheFormKeyOrTheName(String raw, PrivacyRequestType expected) {
        assertThat(PrivacyRequestType.parse(raw)).contains(expected);
    }

    @Test
    void everyProblemIsReportedPerField() {
        assertThatThrownBy(() -> PrivacyRequest.submit(UUID.randomUUID(), 1, new Submission("sell", "ada@", "ZZ",
                "x".repeat(PrivacyRequest.MAX_TEXT + 1), null), null, NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("content.invalid-privacy-request");
                    assertThat(ex.fieldErrors()).containsOnlyKeys("type", "email", "state", "details");
                    assertThat(ex.fieldErrors().get("email")).isEqualTo("Enter the email address on your account");
                    assertThat(ex.fieldErrors().get("state")).isEqualTo("Choose your state");
                    assertThat(ex.fieldErrors().get("type")).isEqualTo("Choose what you would like to do");
                });
    }

    @Test
    void unknownLocalesFallBackToEnglishAndBlankDetailsAreDropped() {
        PrivacyRequest r = PrivacyRequest.submit(UUID.randomUUID(), 7, new Submission("access", "a@b.io", "DC", "  ",
                "de"), " ", NOW);
        assertThat(r.reference()).isEqualTo("PR-000007");
        assertThat(r.locale()).isEqualTo("en");
        assertThat(r.details()).isNull();
        assertThat(r.accountSubject()).isEmpty();
    }

    @Test
    void aDenialNeedsAReasonAndAResolvedRequestCannotBeResolvedAgain() {
        PrivacyRequest r = received();
        assertThatThrownBy(() -> r.resolve(PrivacyRequestStatus.DENIED, " ", "staff-1", NOW))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("note"));
        assertThatThrownBy(() -> r.resolve(PrivacyRequestStatus.RECEIVED, "x", "staff-1", NOW))
                .isInstanceOf(ContentRuleViolation.class);

        PrivacyRequest done = r.resolve(PrivacyRequestStatus.COMPLETED, "", "staff-1", NOW.plusSeconds(60));
        assertThat(done.status()).isEqualTo(PrivacyRequestStatus.COMPLETED);
        assertThat(done.resolutionNote()).isNull();
        assertThat(done.resolvedBy()).isEqualTo("staff-1");
        assertThat(done.overdue(Instant.parse("2027-01-01T00:00:00Z"))).isFalse();
        assertThatThrownBy(() -> done.resolve(PrivacyRequestStatus.DENIED, "Duplicate", "staff-2", NOW))
                .isInstanceOf(ContentStateConflict.class);
    }
}
