package com.afrotransact.bookings.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReplyDto;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReviewResponse;
import com.afrotransact.bookings.application.ReviewService.PublicReviews;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.bookings.port.ReviewRepository.ReviewPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the review endpoints (moderation, provider replies, public lists). */
final class ReviewDtos {

    private ReviewDtos() {
    }

    @Schema(name = "ReviewApproveRequest")
    record ApproveRequest(@Size(max = Review.MAX_NOTE) String note) {
    }

    @Schema(name = "ReviewRejectRequest", description = "note is required for reasonCode OTHER")
    record RejectRequest(@NotNull ReviewRejectReason reasonCode, @Size(max = Review.MAX_NOTE) String note) {
    }

    @Schema(name = "ReviewReplyRequest", description = "The provider replies once to a published review")
    record ReplyRequest(@NotBlank @Size(max = Review.MAX_REPLY) String text) {
    }

    @Schema(name = "ReviewPage")
    record ReviewPageResponse(List<ReviewResponse> items, long total, int page, int size) {

        static ReviewPageResponse of(ReviewPage p) {
            return new ReviewPageResponse(p.items().stream().map(ReviewResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    @Schema(name = "PublicReview", description = "An approved review as shoppers see it (no customer id)")
    record PublicReviewDto(UUID reviewId, UUID serviceId, String serviceName, String reviewerName, int rating,
                           String text, Instant createdAt, ReplyDto reply) {

        static PublicReviewDto of(Review r) {
            return new PublicReviewDto(r.bookingId(), r.serviceId(), r.serviceName(), r.reviewerName(), r.rating(),
                    r.text(), r.createdAt(), ReplyDto.of(r.reply()));
        }
    }

    @Schema(name = "PublicReviewPage", description = "Approved reviews, newest first, with the rating they make up")
    record PublicReviewPageResponse(
            @Schema(description = "Average of the approved reviews (one decimal); 0 without reviews") double rating,
            @Schema(description = "Number of approved reviews") int ratingCount,
            List<PublicReviewDto> items, long total, int page, int size) {

        static PublicReviewPageResponse of(PublicReviews r) {
            ReviewPage p = r.page();
            return new PublicReviewPageResponse(r.rating().average(), r.rating().count(),
                    p.items().stream().map(PublicReviewDto::of).toList(), p.total(), p.page(), p.size());
        }
    }
}
