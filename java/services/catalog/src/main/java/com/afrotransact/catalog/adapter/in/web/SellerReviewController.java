package com.afrotransact.catalog.adapter.in.web;

import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ReviewDtos.ReplyRequest;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.SellerReviewPageResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.SellerReviewResponse;
import com.afrotransact.catalog.application.SellerReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller reviews", description = "The product owner sees the published reviews of its products and replies "
        + "once to each (catalog:write). Sellers cannot edit or hide a review; they can report it.")
@RestController
class SellerReviewController {

    private final SellerReviewService reviews;

    SellerReviewController(SellerReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Published reviews of my products, newest first (catalog:write)",
            description = "Sellers see their own organisation; staff name it with orgId. The customer's identity is "
                    + "left out. unanswered=true lists reviews without a reply.")
    @GetMapping("/v1/seller/reviews")
    SellerReviewPageResponse list(@RequestParam(required = false) String orgId,
                                  @RequestParam(defaultValue = "false") boolean unanswered,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return SellerReviewPageResponse.of(reviews.list(orgId, unanswered, page, size));
    }

    @Operation(summary = "Reply to a published review, once (catalog:write, members of the owning organisation)",
            description = "409 catalog.review-not-published before approval, 409 catalog.review-already-replied the "
                    + "second time; catalog.review.replied. The reply is public and cannot be edited.")
    @PostMapping("/v1/product-reviews/{reviewId}/reply")
    SellerReviewResponse reply(@PathVariable UUID reviewId, @Valid @RequestBody ReplyRequest body) {
        return SellerReviewResponse.of(reviews.reply(reviewId, body.text()));
    }
}
