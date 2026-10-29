package com.afrotransact.catalog.adapter.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ReviewDtos.EditReviewRequest;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.MyReviewResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.OwnReviewResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.PublicReviewPageResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.RatingResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.ReportResultResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.ReportReviewRequest;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.WriteReviewRequest;
import com.afrotransact.catalog.application.ProductReviewService;
import com.afrotransact.catalog.application.ProductReviewService.ReportResult;
import com.afrotransact.catalog.application.ReviewSettings;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ReviewSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product reviews", description = "Verified-purchase ratings and reviews (S324): customers who received a "
        + "product review it once, public reads of the published reviews and the rating, and reports")
@RestController
class ProductReviewController {

    private final ProductReviewService reviews;
    private final ReviewSettings settings;
    private final Clock clock;

    ProductReviewController(ProductReviewService reviews, ReviewSettings settings, Clock clock) {
        this.reviews = reviews;
        this.settings = settings;
        this.clock = clock;
    }

    @Operation(summary = "My standing with a product: may I review it, and my review (signed-in customer)",
            description = "eligible is true when orders-service says the customer received the product and they have "
                    + "no live review yet. A review in any state is returned, with the moderation reason when it was "
                    + "refused or removed. 503 catalog.purchase-check-unavailable when orders cannot answer.")
    @GetMapping("/v1/products/{atin}/reviews/mine")
    MyReviewResponse mine(@PathVariable String atin) {
        return MyReviewResponse.of(reviews.mine(atin), window(), Instant.now(clock));
    }

    @Operation(summary = "Review a product I received (signed-in customer)",
            description = "403 catalog.review-not-verified-purchase without a delivered order line for the product; "
                    + "409 catalog.review-already-exists the second time. The review is published at once or, when the "
                    + "automatic checks (profanity, links, personal data) flag it, waits for moderation (status "
                    + "PENDING). Publishes catalog.review.published or catalog.review.submitted.")
    @PostMapping("/v1/products/{atin}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    OwnReviewResponse write(@PathVariable String atin, @Valid @RequestBody WriteReviewRequest body) {
        return own(reviews.write(atin, body.reviewerName(), body.toDraft()));
    }

    @Operation(summary = "Edit my review within 30 days (signed-in customer)",
            description = "The checks run again. 409 catalog.review-edit-window-closed after the window. An edit of a "
                    + "rejected or removed review always goes back to moderation.")
    @PutMapping("/v1/products/{atin}/reviews/mine")
    OwnReviewResponse edit(@PathVariable String atin, @Valid @RequestBody EditReviewRequest body) {
        return own(reviews.edit(atin, body.toDraft()));
    }

    @Operation(summary = "Delete my review (signed-in customer)",
            description = "Any time. The rating is recomputed; the customer can review the product again.")
    @DeleteMapping("/v1/products/{atin}/reviews/mine")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String atin) {
        reviews.delete(atin);
    }

    @Operation(summary = "Report a published review (signed-in customer, or a member of the product's seller)",
            description = "One report per reporter and review: a repeated report returns 200 with created=false. The "
                    + "first report of a text queues the review in trust-ops (catalog.review.reported); sellers can "
                    + "report but never edit or hide a review.")
    @PostMapping("/v1/product-reviews/{reviewId}/reports")
    ResponseEntity<ReportResultResponse> report(@PathVariable UUID reviewId,
                                                @Valid @RequestBody ReportReviewRequest body) {
        ReportResult r = reviews.report(reviewId, body.reasonCode(), body.details());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new ReportResultResponse(r.created(), r.reports()));
    }

    @Operation(summary = "Published reviews of a live product, with its rating",
            description = "Sorted NEWEST (default), HIGHEST or LOWEST, optionally only reviews with the given stars. "
                    + "Reviewers show as first name and last initial, always Verified purchase.")
    @SecurityRequirements
    @GetMapping("/v1/public/products/{atin}/reviews")
    ResponseEntity<PublicReviewPageResponse> publicReviews(@PathVariable String atin,
                                                           @RequestParam(required = false) Integer stars,
                                                           @RequestParam(defaultValue = "NEWEST") ReviewSort sort,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(PublicReviewPageResponse.of(reviews.publicReviews(atin, stars, sort, page, size)));
    }

    @Operation(summary = "A live product's rating: average, count and star breakdown")
    @SecurityRequirements
    @GetMapping("/v1/public/products/{atin}/rating")
    ResponseEntity<RatingResponse> rating(@PathVariable String atin) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(RatingResponse.of(reviews.rating(atin)));
    }

    private OwnReviewResponse own(ProductReview review) {
        return OwnReviewResponse.of(review, window(), Instant.now(clock));
    }

    private Duration window() {
        return settings.editWindow();
    }
}
