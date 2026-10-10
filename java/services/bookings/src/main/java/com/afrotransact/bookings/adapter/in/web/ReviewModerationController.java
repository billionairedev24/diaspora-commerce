package com.afrotransact.bookings.adapter.in.web;

import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReviewResponse;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.ApproveRequest;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.RejectRequest;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.ReviewPageResponse;
import com.afrotransact.bookings.application.ReviewService;
import com.afrotransact.bookings.domain.ReviewStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review moderation", description = "Trust and safety staff and the trust-ops service client "
        + "(bookings:reviews:moderate, principal type STAFF or SERVICE) approve or reject customer reviews before "
        + "they are public. Decisions are idempotent, so a retried queue decision is safe.")
@RestController
@RequestMapping("/v1/reviews")
class ReviewModerationController {

    private final ReviewService reviews;

    ReviewModerationController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Reviews by moderation state (default PENDING, oldest first)")
    @GetMapping
    ReviewPageResponse reviewModerationQueue(@RequestParam(required = false) ReviewStatus status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ReviewPageResponse.of(reviews.moderationQueue(status, page, size));
    }

    @Operation(summary = "One review (the review id is the booking id)")
    @GetMapping("/{bookingId}")
    ReviewResponse reviewForModeration(@PathVariable UUID bookingId) {
        return ReviewResponse.of(reviews.forModeration(bookingId));
    }

    @Operation(summary = "Publish a review", description = "It is listed publicly and counts in ratings; "
            + "booking.review.approved. Also reinstates a rejected review. Approving an approved review changes "
            + "nothing.")
    @PostMapping("/{bookingId}/approve")
    ReviewResponse approveReview(@PathVariable UUID bookingId,
                                 @Valid @RequestBody(required = false) ApproveRequest body) {
        return ReviewResponse.of(reviews.approve(bookingId, body == null ? null : body.note()));
    }

    @Operation(summary = "Remove a review", description = "Never public (or taken down if it was); "
            + "booking.review.rejected. 422 bookings.invalid-moderation without a note for reasonCode OTHER.")
    @PostMapping("/{bookingId}/reject")
    ReviewResponse rejectReview(@PathVariable UUID bookingId, @Valid @RequestBody RejectRequest body) {
        return ReviewResponse.of(reviews.reject(bookingId, body.reasonCode(), body.note()));
    }
}
