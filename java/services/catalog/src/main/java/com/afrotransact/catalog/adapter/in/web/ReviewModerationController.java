package com.afrotransact.catalog.adapter.in.web;

import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ReviewDtos.ApproveReviewRequest;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.RemoveReviewRequest;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.StaffReviewPageResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.StaffReviewResponse;
import com.afrotransact.catalog.application.ReviewModerationService;
import com.afrotransact.catalog.domain.ReviewStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review moderation", description = "Trust and safety staff and the trust-ops service client "
        + "(catalog:moderate) approve, reject and remove product reviews. Decisions are idempotent, so a retried "
        + "queue decision is safe; the product's rating follows each decision.")
@RestController
class ReviewModerationController {

    private final ReviewModerationService reviews;

    ReviewModerationController(ReviewModerationService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Product reviews by state, newest first (catalog:moderate)")
    @GetMapping("/v1/product-reviews")
    StaffReviewPageResponse list(@RequestParam(required = false) ReviewStatus status,
                                 @RequestParam(required = false) String atin,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return StaffReviewPageResponse.of(reviews.list(status, atin, page, size));
    }

    @Operation(summary = "One product review (catalog:moderate)")
    @GetMapping("/v1/product-reviews/{reviewId}")
    StaffReviewResponse get(@PathVariable UUID reviewId) {
        return StaffReviewResponse.of(reviews.get(reviewId));
    }

    @Operation(summary = "Publish a review", description = "A pending review becomes public and counts in the rating; "
            + "also reinstates a rejected or removed one. Approving a published review changes nothing. "
            + "catalog.review.published.")
    @PostMapping("/v1/product-reviews/{reviewId}/approve")
    StaffReviewResponse approve(@PathVariable UUID reviewId,
                                @Valid @RequestBody(required = false) ApproveReviewRequest body) {
        return StaffReviewResponse.of(reviews.approve(reviewId, body == null ? null : body.note()));
    }

    @Operation(summary = "Reject a review that is waiting for moderation",
            description = "The customer sees the reason and may edit the review (which goes back to moderation). "
                    + "409 catalog.review-not-pending for a published review: remove it instead. "
                    + "422 without a note for reasonCode OTHER. catalog.review.rejected.")
    @PostMapping("/v1/product-reviews/{reviewId}/reject")
    StaffReviewResponse reject(@PathVariable UUID reviewId, @Valid @RequestBody RemoveReviewRequest body) {
        return StaffReviewResponse.of(reviews.reject(reviewId, body.reasonCode(), body.note()));
    }

    @Operation(summary = "Take a review down, published or not",
            description = "It stops being public and counting in the rating; the customer sees the reason. "
                    + "catalog.review.removed.")
    @PostMapping("/v1/product-reviews/{reviewId}/remove")
    StaffReviewResponse remove(@PathVariable UUID reviewId, @Valid @RequestBody RemoveReviewRequest body) {
        return StaffReviewResponse.of(reviews.remove(reviewId, body.reasonCode(), body.note()));
    }
}
