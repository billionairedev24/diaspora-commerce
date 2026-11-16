package com.afrotransact.catalog.adapter.in.module;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.afrotransact.catalog.api.ModerationApi;
import com.afrotransact.catalog.application.ListingModerationService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.ReviewModerationService;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ModerationApi}: the use cases behind the product and product-review moderation endpoints, entered
 * under the calling module's principal. The services require {@code catalog:moderate} exactly as they do for a token (the controllers'
 * {@code @StaffOrServiceOnly} is satisfied by a service principal, which the caller always is). The request bodies' constraints are
 * applied here as the HTTP validation does: a product rejection needs a note (at most 1000 characters), a takedown note at most 1000,
 * a review note at most {@code ProductReview.MAX_NOTE}.
 */
@Component
class ModerationApiAdapter implements ModerationApi {

    private static final int MAX_PRODUCT_NOTE = 1000;

    private final ProductService products;
    private final ListingModerationService listings;
    private final ReviewModerationService reviews;
    private final ModuleEntry entry;

    ModerationApiAdapter(ProductService products, ListingModerationService listings, ReviewModerationService reviews,
                         ActorResolver actors) {
        this.products = products;
        this.listings = listings;
        this.reviews = reviews;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void approveProduct(UUID productId) {
        entry.runVoid(() -> products.approve(productId), CatalogFailures::map);
    }

    @Override
    public void rejectProduct(UUID productId, String note) {
        entry.runVoid(() -> {
            if (note == null || note.isBlank() || note.length() > MAX_PRODUCT_NOTE) {
                throw invalid("note");
            }
            products.reject(productId, note);
        }, CatalogFailures::map);
    }

    @Override
    public void takeDownProduct(UUID productId, String listingReasonCode, String note) {
        entry.runVoid(() -> {
            if (note != null && note.length() > MAX_PRODUCT_NOTE) {
                throw invalid("note");
            }
            listings.takeDown(productId, reason(listingReasonCode, ListingReasonCode::valueOf), note);
        }, CatalogFailures::map);
    }

    @Override
    public void approveReview(UUID reviewId, String note) {
        entry.runVoid(() -> reviews.approve(reviewId, note), CatalogFailures::map);
    }

    @Override
    public void rejectReview(UUID reviewId, String reviewReasonCode, String note) {
        entry.runVoid(() -> reviews.reject(reviewId, reason(reviewReasonCode, ReviewReasonCode::valueOf), note),
                CatalogFailures::map);
    }

    @Override
    public void removeReview(UUID reviewId, String reviewReasonCode, String note) {
        entry.runVoid(() -> reviews.remove(reviewId, reason(reviewReasonCode, ReviewReasonCode::valueOf), note),
                CatalogFailures::map);
    }

    private static <E> E reason(String name, Function<String, E> parse) {
        if (name == null) {
            throw invalid("reasonCode");
        }
        try {
            return parse.apply(name);
        } catch (IllegalArgumentException ex) {
            throw invalid("reasonCode");
        }
    }

    /** What the request validation answers over HTTP for a body that breaks its constraints. */
    private static ModuleApiException invalid(String field) {
        return new ModuleApiException(400, null, "Request validation failed", List.of(field + ": invalid"), null);
    }
}
