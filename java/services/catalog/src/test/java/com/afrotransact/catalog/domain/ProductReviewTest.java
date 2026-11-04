package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductReviewTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final ReviewContentPolicy POLICY = new DefaultReviewContentPolicy();
    private static final PurchaseProof PROOF = new PurchaseProof("order-1", "AT-2026-0001", T0.minusSeconds(3600));
    private static final ReviewPhoto PHOTO = new ReviewPhoto("https://utfs.io/f/jollof.jpg");

    private static Product product() {
        return new Product(UUID.randomUUID(), new Atin("AT12345678"), new ListingOwner(ListingOwnerKind.SELLER, "org-1"),
                UUID.randomUUID(), null, "grocery", "Jollof Spice Mix 500g", Map.of(), List.of(), "", List.of(),
                ProductStatus.LIVE, null, T0, T0, 0);
    }

    private static ReviewDraft draft(int stars, String text) {
        return new ReviewDraft(stars, "Great", text, List.of(PHOTO));
    }

    private static ProductReview written(String text) {
        return ProductReview.write(UUID.randomUUID(), product(), "cust-1", "Adaeze Nwosu", PROOF, draft(5, text),
                POLICY, T0);
    }

    @Test
    void aCleanReviewIsPublishedAtOnceWithAFirstNameAndInitial() {
        ProductReview r = written("Tastes like home");
        assertThat(r.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(r.published()).isTrue();
        assertThat(r.reviewerName()).isEqualTo("Adaeze N.");
        assertThat(r.atin()).isEqualTo("AT12345678");
        assertThat(r.sellerOrgId()).isEqualTo("org-1");
        assertThat(r.orderNumber()).isEqualTo("AT-2026-0001");
        assertThat(r.moderation().by()).isEqualTo(ProductReview.AUTO_CHECKS);
        assertThat(r.flags()).isEmpty();
    }

    @Test
    void aFlaggedReviewWaitsForModeration() {
        ProductReview r = written("Better deals at www.cheap.shop");
        assertThat(r.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(r.flags()).containsExactly(ReviewFlag.LINK);
        assertThat(r.moderation()).isNull();
        assertThat(r.published()).isFalse();
    }

    @Test
    void displayNamesShowTheFirstNameAndLastInitialOnly() {
        assertThat(ProductReview.displayName("Adaeze Nwosu")).isEqualTo("Adaeze N.");
        assertThat(ProductReview.displayName("  mary   jane  watson ")).isEqualTo("mary W.");
        assertThat(ProductReview.displayName("Funmi")).isEqualTo("Funmi");
        assertThat(ProductReview.displayName(null)).isEqualTo("Verified customer");
        assertThat(ProductReview.displayName("   ")).isEqualTo("Verified customer");
        assertThat(ProductReview.displayName("Ada élodie")).isEqualTo("Ada É.");
    }

    @Test
    void starsAreRequiredAndTextAndPhotosAreLimited() {
        assertThatThrownBy(() -> ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(0, null, null, null), POLICY, T0)).isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("rating"));
        assertThatThrownBy(() -> ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(6, null, null, null), POLICY, T0)).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> written("x".repeat(4001))).isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("text"));
        ProductReview max = written("x".repeat(4000));
        assertThat(max.content().text()).hasSize(4000);

        List<ReviewPhoto> six = List.of(PHOTO, PHOTO, PHOTO, PHOTO, PHOTO, PHOTO);
        assertThatThrownBy(() -> ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(4, null, null, six), POLICY, T0)).isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("photos"));
        assertThat(ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(4, null, null, six.subList(0, 5)), POLICY, T0).content().photos()).hasSize(5);
        assertThatThrownBy(() -> ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(4, null, null, List.of(new ReviewPhoto("http://insecure.example/a.jpg"))), POLICY, T0))
                .isInstanceOf(CatalogRuleViolation.class);
        // title and text are optional
        assertThat(ProductReview.write(UUID.randomUUID(), product(), "c", "A B", PROOF,
                new ReviewDraft(3, "  ", "", null), POLICY, T0).content().title()).isNull();
    }

    @Test
    void aCleanEditStaysPublishedAndAFlaggedEditGoesBackToModeration() {
        ProductReview r = written("Good");
        ProductReview edited = r.edit(draft(4, "Still good after a month"), POLICY, WINDOW, T0.plusSeconds(86_400));
        assertThat(edited.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(edited.rating()).isEqualTo(4);
        assertThat(edited.createdAt()).isEqualTo(T0);

        ProductReview flagged = r.edit(draft(4, "mail me a@b.co"), POLICY, WINDOW, T0.plusSeconds(10));
        assertThat(flagged.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(flagged.flags()).containsExactly(ReviewFlag.EMAIL);
    }

    @Test
    void editsStopAfterThirtyDays() {
        ProductReview r = written("Good");
        Instant lastMoment = T0.plus(WINDOW).minusSeconds(1);
        assertThat(r.editable(WINDOW, lastMoment)).isTrue();
        assertThat(r.edit(draft(3, "ok"), POLICY, WINDOW, lastMoment).rating()).isEqualTo(3);
        assertThat(r.editableUntil(WINDOW)).isEqualTo(T0.plus(WINDOW));
        assertThatThrownBy(() -> r.edit(draft(3, "ok"), POLICY, WINDOW, T0.plus(WINDOW)))
                .isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-edit-window-closed");
    }

    @Test
    void anEditOfARefusedReviewAlwaysGoesBackToModeration() {
        ProductReview pending = written("see www.x.shop");
        ProductReview rejected = pending.reject("staff-1", ReviewReasonCode.SPAM, null, T0.plusSeconds(60));
        assertThat(rejected.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(rejected.moderation().reason()).isEqualTo(ReviewReasonCode.SPAM);
        ProductReview rewritten = rejected.edit(draft(2, "Clean words now"), POLICY, WINDOW, T0.plusSeconds(120));
        assertThat(rewritten.status()).isEqualTo(ReviewStatus.PENDING);

        ProductReview removed = written("Fine").remove("staff-1", ReviewReasonCode.OFF_TOPIC, null, T0.plusSeconds(5));
        assertThat(removed.edit(draft(5, "Reworded"), POLICY, WINDOW, T0.plusSeconds(60)).status())
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void editingResetsTheReportsOfTheOldText() {
        ProductReview reported = written("Fine").withReportCount(3);
        assertThat(reported.reportCount()).isEqualTo(3);
        assertThat(reported.edit(draft(5, "Fine, edited"), POLICY, WINDOW, T0.plusSeconds(5)).reportCount()).isZero();
    }

    @Test
    void deletingIsFinalAndRepeatable() {
        ProductReview r = written("Fine");
        ProductReview deleted = r.delete(T0.plusSeconds(5));
        assertThat(deleted.status()).isEqualTo(ReviewStatus.DELETED);
        assertThat(deleted.delete(T0.plusSeconds(9))).isSameAs(deleted);
        assertThatThrownBy(() -> deleted.edit(draft(5, "x"), POLICY, WINDOW, T0.plusSeconds(9)))
                .isInstanceOf(ReviewStateConflict.class);
        assertThatThrownBy(() -> deleted.approve("staff", null, T0)).isInstanceOf(ReviewStateConflict.class);
    }

    @Test
    void moderationApprovesRejectsAndRemovesIdempotently() {
        ProductReview pending = written("call 713 555 0142");
        ProductReview approved = pending.approve("staff-1", "Phone number is the shop's", T0.plusSeconds(5));
        assertThat(approved.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(approved.moderation().by()).isEqualTo("staff-1");
        assertThat(approved.approve("staff-2", null, T0.plusSeconds(9))).isSameAs(approved);

        assertThatThrownBy(() -> approved.reject("staff-1", ReviewReasonCode.SPAM, null, T0))
                .isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-not-pending");

        ProductReview removed = approved.remove("staff-1", ReviewReasonCode.PERSONAL_INFORMATION, "Phone number",
                T0.plusSeconds(20));
        assertThat(removed.status()).isEqualTo(ReviewStatus.REMOVED);
        assertThat(removed.published()).isFalse();
        assertThat(removed.remove("staff-3", ReviewReasonCode.SPAM, null, T0.plusSeconds(30))).isSameAs(removed);
        assertThat(removed.reject("staff-3", ReviewReasonCode.SPAM, null, T0.plusSeconds(30))).isSameAs(removed);
        // a wrong call can be reversed
        assertThat(removed.approve("staff-4", null, T0.plusSeconds(40)).status()).isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    void aReasonIsRequiredAndOtherNeedsANote() {
        ProductReview pending = written("www.x.shop");
        assertThatThrownBy(() -> pending.reject("s", null, null, T0)).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> pending.reject("s", ReviewReasonCode.OTHER, " ", T0))
                .isInstanceOf(CatalogRuleViolation.class);
        assertThat(pending.reject("s", ReviewReasonCode.OTHER, "Not a review", T0).moderation().note())
                .isEqualTo("Not a review");
        assertThatThrownBy(() -> pending.reject("s", ReviewReasonCode.SPAM, "n".repeat(501), T0))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void theOwnerRepliesOnceToAPublishedReview() {
        ProductReview r = written("Fine");
        ProductReview replied = r.replyBy("member-1", "  Thank you!  ", T0.plusSeconds(60));
        assertThat(replied.reply().text()).isEqualTo("Thank you!");
        assertThat(replied.reply().by()).isEqualTo("member-1");
        assertThatThrownBy(() -> replied.replyBy("member-1", "Again", T0.plusSeconds(90)))
                .isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-already-replied");
        assertThatThrownBy(() -> written("www.x.shop").replyBy("m", "Hi", T0)).isInstanceOf(ReviewStateConflict.class)
                .extracting(e -> ((ReviewStateConflict) e).code()).isEqualTo("catalog.review-not-published");
        assertThatThrownBy(() -> r.replyBy("m", " ", T0)).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> r.replyBy("m", "r".repeat(1001), T0)).isInstanceOf(CatalogRuleViolation.class);
    }
}
