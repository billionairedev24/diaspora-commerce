package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** S232 moderation and provider reply rules. */
class ReviewTest {

    private static final Instant NOW = Fixtures.NOW;

    private static Review pending() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        b.complete(Party.PROVIDER, Fixtures.START.plus(Duration.ofHours(6)));
        return Review.of(b, 4, "Lovely braids", NOW);
    }

    @Test
    void newReviewsWaitForModerationUnderAPublicDisplayName() {
        Review r = pending();
        assertThat(r.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(r.published()).isFalse();
        assertThat(r.reviewerName()).isEqualTo("Adaeze N.");
        assertThat(r.serviceName()).isEqualTo("Knotless box braids · medium");
        assertThat(Review.displayName("  Kwame  ")).isEqualTo("Kwame");
        assertThat(Review.displayName(null)).isEqualTo("Verified customer");
    }

    @Test
    void moderationApprovesOrRejectsIdempotently() {
        Review approved = pending().approve("staff-1", null, NOW);
        assertThat(approved.published()).isTrue();
        assertThat(approved.approve("staff-2", null, NOW)).isSameAs(approved);

        assertThatThrownBy(() -> approved.reject("staff-1", ReviewRejectReason.OTHER, " ", NOW))
                .isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> approved.reject("staff-1", null, null, NOW))
                .isInstanceOf(BookingsRuleViolation.class);
        Review removed = approved.reject("staff-1", ReviewRejectReason.SPAM, null, NOW);
        assertThat(removed.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(removed.moderation().reason()).isEqualTo(ReviewRejectReason.SPAM);
        assertThat(removed.reject("staff-2", ReviewRejectReason.ABUSIVE, null, NOW)).isSameAs(removed);
        assertThat(removed.approve("staff-3", "Appeal upheld", NOW).published()).isTrue();
    }

    @Test
    void providersReplyOnceToPublishedReviews() {
        Review r = pending();
        assertThatThrownBy(() -> r.replyBy("owner", "Thanks", NOW)).isInstanceOf(BookingsStateConflict.class);
        Review approved = r.approve("staff-1", null, NOW);
        assertThatThrownBy(() -> approved.replyBy("owner", "   ", NOW)).isInstanceOf(BookingsRuleViolation.class);
        Review replied = approved.replyBy("owner", "  Thank you!  ", NOW);
        assertThat(replied.reply().text()).isEqualTo("Thank you!");
        assertThatThrownBy(() -> replied.replyBy("owner", "Again", NOW)).isInstanceOf(BookingsStateConflict.class);
    }
}
