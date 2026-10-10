package com.afrotransact.bookings.adapter.in.web;

import java.time.Duration;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReviewResponse;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.PublicReviewPageResponse;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.ReplyRequest;
import com.afrotransact.bookings.adapter.in.web.ReviewDtos.ReviewPageResponse;
import com.afrotransact.bookings.application.ReviewService;
import com.afrotransact.bookings.domain.ReviewStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reviews", description = "Approved reviews per provider and per service (public), the provider's own "
        + "reviews and their single reply.")
@RestController
class ReviewsController {

    private final ReviewService reviews;

    ReviewsController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Approved reviews of a provider, newest first, with its rating")
    @SecurityRequirements
    @GetMapping("/v1/public/providers/{orgId}/reviews")
    ResponseEntity<PublicReviewPageResponse> publicProviderReviews(
            @PathVariable String orgId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(PublicReviewPageResponse.of(reviews.publicForProvider(orgId, page, size)));
    }

    @Operation(summary = "Approved reviews of a service, newest first, with its rating")
    @SecurityRequirements
    @GetMapping("/v1/public/services/{serviceId}/reviews")
    ResponseEntity<PublicReviewPageResponse> publicServiceReviews(
            @PathVariable UUID serviceId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(PublicReviewPageResponse.of(reviews.publicForService(serviceId, page, size)));
    }

    @Operation(summary = "The provider's reviews in every state (bookings:read)",
            description = "Pending and rejected reviews are visible to the provider but never public.")
    @GetMapping("/v1/providers/{orgId}/reviews")
    ReviewPageResponse providerReviewList(@PathVariable String orgId,
                                          @RequestParam(required = false) ReviewStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ReviewPageResponse.of(reviews.providerReviews(orgId, status, page, size));
    }

    @Operation(summary = "Reply to a published review, once (Owner / Manager: bookings:manage)",
            description = "409 bookings.review-not-published before approval, 409 bookings.already-replied the "
                    + "second time; booking.review.replied.")
    @PostMapping("/v1/providers/{orgId}/reviews/{bookingId}/reply")
    ReviewResponse replyToReview(@PathVariable String orgId, @PathVariable UUID bookingId,
                                 @Valid @RequestBody ReplyRequest body) {
        return ReviewResponse.of(reviews.reply(orgId, bookingId, body.text()));
    }
}
