package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ModerationItemTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    private final SlaPolicy policy = new DefaultSlaPolicy(SlaTargets.defaults());
    private final ProductSubmission product = new ProductSubmission(UUID.randomUUID(), "AT0000ABCD", "org_ade",
            "SELLER", "Bitter kola that cures diabetes", "herbal");

    private static TriageHint hint(String risk) {
        return new TriageHint(risk, "Unapproved health claim in the title",
                List.of(new AiFlag("HEALTH_CLAIM", "Claims to cure a disease", List.of("title"))), "model-x");
    }

    @Test
    void queuedWithoutAiHasNormalPriorityAndTheDefaultSla() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy);

        assertThat(item.status()).isEqualTo(ModerationStatus.PENDING);
        assertThat(item.triageStatus()).isEqualTo(TriageStatus.UNAVAILABLE);
        assertThat(item.priority()).isEqualTo(ModerationPriority.NORMAL);
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    void highRiskHintRaisesPriorityAndShortensTheSla() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, hint("HIGH"), T0, policy);

        assertThat(item.priority()).isEqualTo(ModerationPriority.HIGH);
        assertThat(item.triageStatus()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));

        ModerationItem late = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .withTriage(hint("critical"), policy);
        assertThat(late.priority()).isEqualTo(ModerationPriority.HIGH);
        assertThat(late.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
    }

    @Test
    void escalationStartsTheEscalationTimerAndClearsTheAssignee() {
        Instant t1 = T0.plus(Duration.ofHours(2));
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .assign("reviewer-1", T0);
        ModerationItem escalated = item.escalate("Needs legal review", t1, policy);

        assertThat(escalated.status()).isEqualTo(ModerationStatus.ESCALATED);
        assertThat(escalated.assignee()).isNull();
        assertThat(escalated.sla().dueAt()).isEqualTo(t1.plus(Duration.ofHours(8)));
        assertThatThrownBy(() -> escalated.escalate("again", t1, policy)).isInstanceOf(IllegalStateException.class);
        assertThat(escalated.withTriage(hint("HIGH"), policy).sla()).isEqualTo(escalated.sla());
    }

    @Test
    void rejectionNeedsAReasonCodeAndANoteForOther() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy);

        assertThatThrownBy(() -> item.reject("r", null, "x", T0)).isInstanceOf(TrustRuleViolation.class);
        assertThatThrownBy(() -> item.reject("r", ModerationReasonCode.OTHER, null, T0))
                .isInstanceOf(TrustRuleViolation.class);
        ModerationItem rejected = item.reject("r", ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM, "Remove the claim",
                T0);
        assertThat(rejected.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(rejected.catalogNote()).isEqualTo("[UNAPPROVED_HEALTH_CLAIM] Unapproved health or medical claim: "
                + "Remove the claim");
    }

    @Test
    void decidedItemsAreFinal() {
        ModerationItem approved = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .approve("r", null, T0);
        assertThat(approved.open()).isFalse();
        assertThatThrownBy(() -> approved.reject("r", ModerationReasonCode.COUNTERFEIT, null, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.assign("r", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.closeUpstream("LIVE", T0)).isInstanceOf(IllegalStateException.class);
    }

    private static ReviewSubmission review(int rating) {
        return new ReviewSubmission(UUID.randomUUID(), "org_braids", UUID.randomUUID(), "Box braids", "cust-1",
                "Adaeze N.", rating, "Lovely work", T0);
    }

    @Test
    void reviewsQueueWithTheListingSlaAndAreAboutTheBooking() {
        ReviewSubmission r = review(4);
        ModerationItem item = ModerationItem.queueReview(UUID.randomUUID(), r, null, T0, policy);

        assertThat(item.type()).isEqualTo(ModerationItemType.SERVICE_REVIEW);
        assertThat(item.serviceReview()).isTrue();
        assertThat(item.product()).isNull();
        assertThat(item.subjectId()).isEqualTo(r.bookingId());
        assertThat(item.orgId()).isEqualTo("org_braids");
        assertThat(item.title()).isEqualTo("★★★★☆ review of Box braids");
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(ModerationItem.queueReview(UUID.randomUUID(), r, hint("HIGH"), T0, policy).sla().dueAt())
                .isEqualTo(T0.plus(Duration.ofHours(4)));

        ModerationItem closed = item.closeUpstream("APPROVED", T0.plusSeconds(60));
        assertThat(closed.status()).isEqualTo(ModerationStatus.CLOSED);
        assertThat(closed.decidedBy()).isEqualTo("bookings");
        assertThat(closed.decisionNote()).isEqualTo("Decided in bookings: APPROVED");
    }

    @Test
    void reasonCodesMustFitTheKindOfItem() {
        ModerationItem reviewItem = ModerationItem.queueReview(UUID.randomUUID(), review(1), null, T0, policy);
        ModerationItem listing = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy);

        assertThatThrownBy(() -> reviewItem.reject("sara", ModerationReasonCode.COUNTERFEIT, null, T0))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("cannot remove a review");
        assertThatThrownBy(() -> listing.reject("sara", ModerationReasonCode.SPAM, null, T0))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("cannot remove a listing");
        assertThat(reviewItem.reject("sara", ModerationReasonCode.ABUSIVE, null, T0).status())
                .isEqualTo(ModerationStatus.REJECTED);
        assertThatThrownBy(() -> reviewItem.reject("sara", ModerationReasonCode.OTHER, null, T0))
                .isInstanceOf(TrustRuleViolation.class);
        assertThat(reviewItem.reject("sara", ModerationReasonCode.OTHER, "Mentions a competitor", T0).reasonCode())
                .isEqualTo(ModerationReasonCode.OTHER);

        assertThat(ModerationReasonCode.OTHER.appliesTo(ModerationItemType.SERVICE_REVIEW)).isTrue();
        assertThat(ModerationReasonCode.OTHER.appliesTo(ModerationItemType.LIVE_LISTING)).isTrue();
        assertThat(ModerationReasonCode.CONFLICT_OF_INTEREST.appliesTo(ModerationItemType.NEW_LISTING)).isFalse();
        assertThat(ModerationReasonCode.WRONG_CATEGORY.appliesTo(ModerationItemType.LIVE_LISTING)).isTrue();
    }

    @Test
    void reviewSubmissionsNeedABookingAProviderAndAValidRating() {
        assertThatThrownBy(() -> review(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> review(6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewSubmission(UUID.randomUUID(), " ", null, null, null, null, 3, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        ReviewSubmission blank = new ReviewSubmission(UUID.randomUUID(), "org_a", null, " ", null, null, 3, "  ",
                null);
        assertThat(blank.text()).isNull();
        assertThat(blank.summary()).isEqualTo("★★★☆☆ review of a service");
    }

    private static ReviewSubmission productReview(int reports) {
        return ReviewSubmission.productReview(UUID.randomUUID(), "org_ade", UUID.randomUUID(), "AT7K2M9QXA",
                "Jollof Spice Mix 500g", "cust-1", "Kemi A.", 2, "Too mild", "Call 0803 123 4567",
                List.of("https://utfs.io/f/a.jpg"), List.of("PHONE"), reports, reports > 0 ? "SPAM" : null, T0);
    }

    @Test
    void productReviewItemsAreReviewsWithTheirOwnSubjectAndReasonCodes() {
        ReviewSubmission r = productReview(0);
        ModerationItem item = ModerationItem.queueProductReview(UUID.randomUUID(), r, null, T0, policy);

        assertThat(item.type()).isEqualTo(ModerationItemType.PRODUCT_REVIEW);
        assertThat(item.type().listing()).isFalse();
        assertThat(item.type().review()).isTrue();
        assertThat(item.productReview()).isTrue();
        assertThat(item.serviceReview()).isFalse();
        assertThat(item.reviewItem()).isTrue();
        assertThat(item.product()).isNull();
        assertThat(item.subjectId()).isEqualTo(r.bookingId());
        assertThat(item.orgId()).isEqualTo("org_ade");
        assertThat(item.title()).isEqualTo("★★☆☆☆ review of Jollof Spice Mix 500g");
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(ModerationItem.queueProductReview(UUID.randomUUID(), r, hint("HIGH"), T0, policy).priority())
                .isEqualTo(ModerationPriority.HIGH);

        assertThat(item.reject("amaka", ModerationReasonCode.PERSONAL_INFORMATION, null, T0).status())
                .isEqualTo(ModerationStatus.REJECTED);
        assertThatThrownBy(() -> item.reject("amaka", ModerationReasonCode.COUNTERFEIT, null, T0))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("cannot remove a review");
        assertThat(ModerationReasonCode.SPAM.appliesTo(ModerationItemType.PRODUCT_REVIEW)).isTrue();
        assertThat(ModerationReasonCode.OTHER.appliesTo(ModerationItemType.PRODUCT_REVIEW)).isTrue();
        assertThat(ModerationReasonCode.COUNTERFEIT.appliesTo(ModerationItemType.PRODUCT_REVIEW)).isFalse();
        assertThatThrownBy(() -> ModerationItem.queueProductReview(UUID.randomUUID(), review(3), null, T0, policy))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsAreCountedOnTheOpenReviewItemAndReportedReviewsSayWhatTheyAre() {
        ModerationItem item = ModerationItem.queueProductReview(UUID.randomUUID(), productReview(1), null, T0, policy);

        assertThat(item.review().reported()).isTrue();
        assertThat(item.title()).isEqualTo("★★☆☆☆ reported review of Jollof Spice Mix 500g");
        ModerationItem again = item.reviewReportedAgain();
        assertThat(again.review().reports()).isEqualTo(2);
        assertThat(again.review().reportReason()).isEqualTo("SPAM");
        assertThat(again.version()).isEqualTo(item.version());
        assertThat(productReview(0).reported()).isFalse();
    }

    @Test
    void aNewerVersionOfTheReviewSupersedesTheOpenItem() {
        ModerationItem item = ModerationItem.queueProductReview(UUID.randomUUID(), productReview(0), null, T0, policy);
        ModerationItem closed = item.supersede(T0.plusSeconds(60));

        assertThat(closed.status()).isEqualTo(ModerationStatus.CLOSED);
        assertThat(closed.decidedBy()).isEqualTo("catalog");
        assertThat(closed.decisionNote()).startsWith("Superseded");
        assertThatThrownBy(() -> closed.supersede(T0)).isInstanceOf(IllegalStateException.class);
        assertThat(item.closeUpstream("PUBLISHED", T0).decisionNote()).isEqualTo("Decided in catalog: PUBLISHED");
    }

    @Test
    void productReviewSubmissionsKeepLongerTextAndNormaliseTheirLists() {
        ReviewSubmission r = ReviewSubmission.productReview(UUID.randomUUID(), "org_a", UUID.randomUUID(), "AT7K2M9QXA",
                " ", "c", "Kemi A.", 5, " ", "x".repeat(3000), null, null, -4, " ", T0);
        assertThat(r.text()).hasSize(3000);
        assertThat(r.title()).isNull();
        assertThat(r.photoUrls()).isEmpty();
        assertThat(r.flags()).isEmpty();
        assertThat(r.reports()).isZero();
        assertThat(r.reportReason()).isNull();
        assertThat(r.summary()).isEqualTo("★★★★★ review of a product");
        // booking reviews still cap at 2000 characters
        assertThat(new ReviewSubmission(UUID.randomUUID(), "org_a", null, null, null, null, 3, "y".repeat(2500), null)
                .text()).hasSize(2000);
    }
}
