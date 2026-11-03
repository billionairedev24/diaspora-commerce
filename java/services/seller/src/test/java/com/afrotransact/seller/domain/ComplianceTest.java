package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ComplianceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private static RequirementsSnapshot snap(List<String> current, List<String> past, List<String> pending,
                                             Instant deadline, String disabled, boolean charges) {
        return new RequirementsSnapshot(current, List.of(), past, pending, List.of(), deadline, disabled, charges,
                true, true, null, Map.of());
    }

    @Test
    void statusDerivation() {
        assertThat(ComplianceStatusPolicy.derive(snap(List.of(), List.of(), List.of(), null, null, true), NOW))
                .isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(ComplianceStatusPolicy.derive(snap(List.of("tos_acceptance"), List.of(), List.of(),
                NOW.plus(Duration.ofDays(12)), null, true), NOW)).isEqualTo(ComplianceStatus.ACTION_NEEDED);
        assertThat(ComplianceStatusPolicy.derive(snap(List.of("representative.dob"), List.of(), List.of(),
                NOW.plus(Duration.ofDays(2)), null, true), NOW)).isEqualTo(ComplianceStatus.RESTRICTED_SOON);
        assertThat(ComplianceStatusPolicy.derive(snap(List.of(), List.of("individual.id_number"), List.of(), null,
                null, true), NOW)).isEqualTo(ComplianceStatus.RESTRICTED);
        assertThat(ComplianceStatusPolicy.derive(snap(List.of(), List.of(), List.of(), null,
                "requirements.past_due", false), NOW)).isEqualTo(ComplianceStatus.RESTRICTED);
        assertThat(ComplianceStatusPolicy.derive(snap(List.of(), List.of(), List.of("company.tax_id"), null, null,
                true), NOW)).isEqualTo(ComplianceStatus.VERIFYING);
    }

    @Test
    void pastDuePausesPayoutsAndClearingResumesThem() {
        ConnectAccount open = ConnectAccount.open("acct_1", "org_1", NOW)
                .apply(snap(List.of(), List.of(), List.of(), null, null, true), 1, NOW).account();
        ComplianceUpdate pastDue = open.apply(snap(List.of(), List.of("individual.id_number"), List.of(), null,
                "requirements.past_due", true), 2, NOW);
        assertThat(pastDue.payouts()).isEqualTo(Enforcement.PAUSE_PAYOUTS);
        assertThat(pastDue.account().payoutsPaused()).isTrue();
        assertThat(pastDue.account().holdReason()).isEqualTo(PayoutHold.AUTO_PAST_DUE);
        assertThat(pastDue.newItems()).containsExactly("individual.id_number");
        assertThat(pastDue.statusChanged()).isTrue();

        ComplianceUpdate again = pastDue.account().apply(snap(List.of(), List.of("individual.id_number"), List.of(),
                null, "requirements.past_due", true), 3, NOW);
        assertThat(again.payouts()).isEqualTo(Enforcement.NONE);
        assertThat(again.requirementsChanged()).isFalse();

        ComplianceUpdate cleared = again.account().apply(snap(List.of(), List.of(), List.of(), null, null, true), 4,
                NOW);
        assertThat(cleared.payouts()).isEqualTo(Enforcement.RESUME_PAYOUTS);
        assertThat(cleared.account().payoutsPaused()).isFalse();
        assertThat(cleared.account().status()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(cleared.account().requirementsVersion()).isEqualTo(4);
    }

    @Test
    void manualHoldsAreNotReleasedAutomatically() {
        ConnectAccount held = ConnectAccount.open("acct_1", "org_1", NOW).pauseManually(NOW);
        assertThat(held.holdReason()).isEqualTo(PayoutHold.MANUAL);
        ComplianceUpdate u = held.apply(snap(List.of(), List.of(), List.of(), null, null, true), 1, NOW);
        assertThat(u.payouts()).isEqualTo(Enforcement.NONE);
        assertThat(u.account().payoutsPaused()).isTrue();
        assertThat(u.account().resumeManually(NOW).payoutsPaused()).isFalse();
    }

    @Test
    void staffCannotReleaseWhileStripeReportsPastDue() {
        ConnectAccount pastDue = ConnectAccount.open("acct_1", "org_1", NOW).apply(snap(List.of(),
                List.of("external_account"), List.of(), null, null, true), 1, NOW).account();
        assertThatThrownBy(() -> pastDue.resumeManually(NOW)).isInstanceOf(SellerStateConflict.class);
    }

    @Test
    void chargesDisabledHidesListingsAndReenabledRestoresThem() {
        ConnectAccount live = ConnectAccount.open("acct_1", "org_1", NOW)
                .apply(snap(List.of(), List.of(), List.of(), null, null, true), 1, NOW).account();
        ComplianceUpdate off = live.apply(snap(List.of(), List.of(), List.of(), null, "requirements.past_due", false),
                2, NOW);
        assertThat(off.listings()).isEqualTo(Enforcement.HIDE_LISTINGS);
        ComplianceUpdate on = off.account().apply(snap(List.of(), List.of(), List.of(), null, null, true), 3, NOW);
        assertThat(on.listings()).isEqualTo(Enforcement.RESTORE_LISTINGS);
        assertThat(on.account().listingsHidden()).isFalse();
    }

    @Test
    void neverEnabledAccountsAreNotHiddenDuringOnboarding() {
        ComplianceUpdate first = ConnectAccount.open("acct_1", "org_1", NOW)
                .apply(snap(List.of("company.tax_id"), List.of(), List.of(), null, null, false), 1, NOW);
        assertThat(first.listings()).isEqualTo(Enforcement.NONE);
    }

    @Test
    void theMirroredPayoutBankSurvivesSnapshotsThatDontListIt() {
        PayoutBank bank = new PayoutBank("ba_1", "STRIPE TEST BANK", "6789", "new");
        ConnectAccount withBank = ConnectAccount.open("acct_1", "org_1", NOW).apply(snap(List.of(), List.of(),
                List.of(), null, null, true).withPayoutBank(bank), 1, NOW).account();
        assertThat(withBank.requirements().payoutBank()).isEqualTo(bank);
        ConnectAccount later = withBank.apply(snap(List.of(), List.of(), List.of(), null, null, true), 2, NOW)
                .account();
        assertThat(later.requirements().payoutBank()).as("kept: the payload did not list external accounts")
                .isEqualTo(bank);
        PayoutBank failed = new PayoutBank("ba_1", "STRIPE TEST BANK", "6789", "errored");
        ConnectAccount errored = later.withPayoutBank(failed, NOW);
        assertThat(errored.externalAccountStatus()).isEqualTo("errored");
        assertThat(errored.requirements().payoutBank().failed()).isTrue();
        assertThat(errored.withoutPayoutBank("ba_other", NOW).requirements().payoutBank()).isNotNull();
        assertThat(errored.withoutPayoutBank("ba_1", NOW).requirements().payoutBank()).isNull();
    }

    @Test
    void deauthorizationRestrictsEverything() {
        ConnectAccount live = ConnectAccount.open("acct_1", "org_1", NOW)
                .apply(snap(List.of(), List.of(), List.of(), null, null, true), 1, NOW).account();
        ComplianceUpdate u = live.deauthorize(NOW);
        assertThat(u.account().status()).isEqualTo(ComplianceStatus.RESTRICTED);
        assertThat(u.payouts()).isEqualTo(Enforcement.PAUSE_PAYOUTS);
        assertThat(u.listings()).isEqualTo(Enforcement.HIDE_LISTINGS);
        assertThat(u.account().holdReason()).isEqualTo(PayoutHold.DEAUTHORIZED);
    }

    @Test
    void remindersAt7And3And1DaysBeforeTheDeadline() {
        Instant deadline = NOW.plus(Duration.ofDays(10));
        assertThat(ReminderSchedule.dueThreshold(deadline, NOW)).isEmpty();
        assertThat(ReminderSchedule.dueThreshold(deadline, deadline.minus(Duration.ofDays(6)))).contains(7);
        assertThat(ReminderSchedule.dueThreshold(deadline, deadline.minus(Duration.ofDays(3)))).contains(3);
        assertThat(ReminderSchedule.dueThreshold(deadline, deadline.minus(Duration.ofHours(30)))).contains(3);
        assertThat(ReminderSchedule.dueThreshold(deadline, deadline.minus(Duration.ofHours(20)))).contains(1);
        assertThat(ReminderSchedule.dueThreshold(deadline, deadline.plusSeconds(1))).isEmpty();
        assertThat(ReminderSchedule.dueThreshold(null, NOW)).isEmpty();
    }

    @Test
    void everyRequirementIsAnsweredInStripesEmbeddedComponents() {
        assertThat(RequirementCatalog.classify("company.tax_id")).isEqualTo(RequirementKind.TAX_ID);
        assertThat(RequirementCatalog.classify("individual.id_number")).isEqualTo(RequirementKind.ID_NUMBER);
        assertThat(RequirementCatalog.classify("person_1Abc.verification.document"))
                .isEqualTo(RequirementKind.VERIFICATION_DOCUMENT);
        assertThat(RequirementCatalog.subject("person_1Abc.verification.document")).isEqualTo("person_1Abc");
        assertThat(RequirementCatalog.classify("company.ownership_declaration.date"))
                .isEqualTo(RequirementKind.OWNERSHIP_DECLARATION);
        assertThat(RequirementCatalog.classify("representative.dob.day")).isEqualTo(RequirementKind.REPRESENTATIVE_DOB);
        assertThat(RequirementCatalog.classify("representative.relationship.title"))
                .isEqualTo(RequirementKind.REPRESENTATIVE_TITLE);
        assertThat(RequirementCatalog.classify("tos_acceptance.date")).isEqualTo(RequirementKind.TOS_ACCEPTANCE);
        assertThat(RequirementCatalog.classify("external_account")).isEqualTo(RequirementKind.BANK_ACCOUNT);
        assertThat(RequirementCatalog.classify("business_profile.mcc")).isEqualTo(RequirementKind.UNSUPPORTED);
        // Stripe-collected accounts: the bank account is changed in account management, the rest in onboarding.
        assertThat(RequirementCatalog.form("external_account").component())
                .isEqualTo(RequirementForm.ACCOUNT_MANAGEMENT);
        for (String key : List.of("company.tax_id", "individual.id_number", "representative.dob.day",
                "tos_acceptance.date", "person_1Abc.verification.document", "business_profile.mcc")) {
            assertThat(RequirementCatalog.form(key).component()).as(key).isEqualTo(RequirementForm.ACCOUNT_ONBOARDING);
        }
        assertThat(RequirementCatalog.form("company.tax_id").label()).isEqualTo("Company tax ID (EIN)");
    }

    @Test
    void verificationRowsComeFromRequirements() {
        RequirementsSnapshot r = new RequirementsSnapshot(List.of(), List.of(), List.of(), List.of(),
                List.of(new RequirementError("company.tax_id", "verification_failed_tax_id_match", "No match")), null,
                null, false, false, true, null, Map.of());
        StripeVerification v = StripeVerification.from("acct_1", r, NOW);
        assertThat(v.taxIdStatus()).isEqualTo("failed");
        assertThat(v.identityStatus()).isEqualTo("verified");
        assertThat(v.detailsSubmitted()).isTrue();
    }
}
