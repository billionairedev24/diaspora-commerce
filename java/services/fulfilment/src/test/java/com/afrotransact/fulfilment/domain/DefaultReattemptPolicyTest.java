package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultReattemptPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-10T20:00:00Z");

    private final ReattemptPolicy policy = new DefaultReattemptPolicy();

    private static DeliveryProblem unavailable(ProblemResolution r, Instant at) {
        return new DeliveryProblem(DeliveryProblemType.CUSTOMER_UNAVAILABLE, r, null, null, at);
    }

    private static List<ContactAttempt> calledTextedWaited(Instant waitStart) {
        return List.of(new ContactAttempt(ContactKind.CALL, T0),
                new ContactAttempt(ContactKind.TEXT, T0.plusSeconds(20)),
                new ContactAttempt(ContactKind.WAIT_STARTED, waitStart));
    }

    @Test
    void customerUnavailableNeedsCallTextAndAFiveMinuteWait() {
        Instant waitStart = T0.plusSeconds(30);
        Instant fiveLater = waitStart.plusSeconds(300);
        for (ProblemResolution r : List.of(ProblemResolution.REATTEMPT, ProblemResolution.PICKUP_POINT,
                ProblemResolution.RETURN_TO_FC)) {
            assertThat(policy.decide(unavailable(r, fiveLater), calledTextedWaited(waitStart), 0)).isNotNull();
        }
        assertThatThrownBy(() -> policy.decide(unavailable(ProblemResolution.REATTEMPT, fiveLater.minusSeconds(1)),
                calledTextedWaited(waitStart), 0))
                .isInstanceOf(FulfilmentRuleViolation.class)
                .hasMessageContaining("wait 5 minutes")
                .extracting("code").isEqualTo("fulfilment.contact-policy-not-met");
        assertThatThrownBy(() -> policy.decide(unavailable(ProblemResolution.RETURN_TO_FC, fiveLater),
                List.of(new ContactAttempt(ContactKind.CALL, T0),
                        new ContactAttempt(ContactKind.WAIT_STARTED, waitStart)), 0))
                .hasMessageContaining("text the customer");
        assertThatThrownBy(() -> policy.decide(unavailable(ProblemResolution.RETURN_TO_FC, fiveLater), List.of(), 0))
                .hasMessageContaining("call the customer").hasMessageContaining("text the customer");
    }

    @Test
    void contactsLoggedAfterTheReportDoNotCount() {
        Instant report = T0.plusSeconds(400);
        List<ContactAttempt> late = List.of(new ContactAttempt(ContactKind.CALL, report.plusSeconds(1)),
                new ContactAttempt(ContactKind.TEXT, T0), new ContactAttempt(ContactKind.WAIT_STARTED, T0));
        assertThatThrownBy(() -> policy.decide(unavailable(ProblemResolution.REATTEMPT, report), late, 0))
                .hasMessageContaining("call the customer");
    }

    @Test
    void outcomesPerResolution() {
        Instant at = T0.plusSeconds(600);
        List<ContactAttempt> ok = calledTextedWaited(T0);
        ProblemDecision retry = policy.decide(unavailable(ProblemResolution.REATTEMPT, at), ok, 0);
        assertThat(retry.stopOutcome()).isEqualTo(StopOutcome.RETRY);
        assertThat(retry.reportFailure()).isFalse();
        ProblemDecision pickupPoint = policy.decide(unavailable(ProblemResolution.PICKUP_POINT, at), ok, 0);
        assertThat(pickupPoint.stopOutcome()).isEqualTo(StopOutcome.FAILED);
        assertThat(pickupPoint.packageStatus()).isEqualTo(PackageStatus.TO_PICKUP_POINT);
        assertThat(pickupPoint.reportFailure()).isTrue();
        ProblemDecision back = policy.decide(unavailable(ProblemResolution.RETURN_TO_FC, at), ok, 0);
        assertThat(back.packageStatus()).isEqualTo(PackageStatus.FAILED);
        assertThat(back.packageStatus().awaitingReturnScan()).isTrue();
    }

    @Test
    void reattemptsAreCapped() {
        Instant at = T0.plusSeconds(600);
        assertThat(policy.decide(unavailable(ProblemResolution.REATTEMPT, at), calledTextedWaited(T0), 1)).isNotNull();
        assertThatThrownBy(() -> policy.decide(unavailable(ProblemResolution.REATTEMPT, at), calledTextedWaited(T0),
                2)).extracting("code").isEqualTo("fulfilment.reattempt-limit");
    }

    @Test
    void otherProblemsNeedNotesPhotosAndAllowedResolutions() {
        DeliveryProblem accessNoPhoto = new DeliveryProblem(DeliveryProblemType.ACCESS_ISSUE,
                ProblemResolution.RETURN_TO_FC, "Gate code 4471 failed", null, T0);
        assertThatThrownBy(() -> policy.decide(accessNoPhoto, List.of(), 0)).extracting("code")
                .isEqualTo("fulfilment.problem-photo-required");
        DeliveryProblem refusedShortNote = new DeliveryProblem(DeliveryProblemType.REFUSED,
                ProblemResolution.RETURN_TO_FC, "no", null, T0);
        assertThatThrownBy(() -> policy.decide(refusedShortNote, List.of(), 0)).extracting("code")
                .isEqualTo("fulfilment.problem-note-required");
        DeliveryProblem refusedRetry = new DeliveryProblem(DeliveryProblemType.REFUSED, ProblemResolution.REATTEMPT,
                "Customer refused", null, T0);
        assertThatThrownBy(() -> policy.decide(refusedRetry, List.of(), 0)).extracting("code")
                .isEqualTo("fulfilment.resolution-not-allowed");
        DeliveryProblem damagedAccepted = new DeliveryProblem(DeliveryProblemType.DAMAGED,
                ProblemResolution.ACCEPTED_WITH_DAMAGE, "Corner crushed", Photos.live("R"), T0);
        assertThat(policy.decide(damagedAccepted, List.of(), 0).stopOutcome()).isEqualTo(StopOutcome.CONTINUE);
        DeliveryProblem missing = new DeliveryProblem(DeliveryProblemType.MISSING, ProblemResolution.REPORT_MISSING,
                "Not in the van", null, T0);
        assertThat(policy.decide(missing, List.of(), 0).packageStatus()).isEqualTo(PackageStatus.MISSING);
        DeliveryProblem unsafeGallery = new DeliveryProblem(DeliveryProblemType.UNSAFE,
                ProblemResolution.PICKUP_POINT, "Loose dog at gate", Photos.gallery("R"), T0);
        assertThatThrownBy(() -> policy.decide(unsafeGallery, List.of(), 0)).extracting("code")
                .isEqualTo("fulfilment.photo-not-live");
    }
}
