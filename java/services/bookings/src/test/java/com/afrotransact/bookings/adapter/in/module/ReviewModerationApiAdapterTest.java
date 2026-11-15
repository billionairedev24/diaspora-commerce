package com.afrotransact.bookings.adapter.in.module;

import java.util.UUID;

import com.afrotransact.bookings.api.ReviewModerationApi;
import com.afrotransact.bookings.application.ReviewService;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** S6 for the review moderation calls: a caller without the moderation scope is refused with 403. */
class ReviewModerationApiAdapterTest {

    private final ReviewModerationApi api = new ReviewModerationApiAdapter(
            InProcessKit.guarded(ReviewService.class, "bookings:reviews:moderate"), InProcessKit.ACTORS);

    @Test
    void approveWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> api.approve(UUID.randomUUID(), "ok"));
    }

    @Test
    void rejectWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"),
                () -> api.reject(UUID.randomUUID(), ReviewRejectReason.values()[0].name(),
                        "no"));
    }
}
