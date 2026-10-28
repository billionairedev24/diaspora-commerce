package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ReviewPhoto;

/**
 * Payload of {@code catalog.review.*} (contracts/asyncapi/fragments/catalog.yaml CatalogReview). Carries what the
 * moderation queue needs to decide without calling back: the text, the stars, who wrote it and for which product,
 * plus the decision endpoints.
 *
 * @param contentType  always {@code PRODUCT_REVIEW} (lets a generic moderation queue route it)
 * @param reviewId     the review id
 * @param sellerOrgId  the product owner (may reply, report and is affected by decisions)
 * @param customerId   the author's token subject; never public
 * @param reviewerName the public display name, e.g. "Adaeze N."
 * @param flags        what the automatic checks found (PROFANITY, LINK, EMAIL, PHONE)
 * @param reportCount  reports on the current text
 * @param reportReason reason of the latest report (catalog.review.reported only)
 * @param approvePath  catalog endpoint that publishes it ({@code catalog:moderate})
 * @param rejectPath   endpoint that refuses a pending review, body {@code {reasonCode, note}}
 * @param removePath   endpoint that takes a review down, body {@code {reasonCode, note}}
 */
public record ReviewEventPayload(String contentType, UUID reviewId, UUID productId, String atin, String productTitle,
                                 String sellerOrgId, String customerId, String reviewerName, int rating, String title,
                                 String text, List<String> photoUrls, String status, List<String> flags,
                                 Instant createdAt, int reportCount, String reportReason, String moderatedBy,
                                 Instant moderatedAt, String reasonCode, String moderationNote, String replyText,
                                 Instant repliedAt, String approvePath, String rejectPath, String removePath) {

    static ReviewEventPayload of(ProductReview r, String reportReason) {
        ProductReview.Moderation m = r.moderation();
        ProductReview.SellerReply reply = r.reply();
        String base = "/v1/product-reviews/" + r.id();
        return new ReviewEventPayload("PRODUCT_REVIEW", r.id(), r.productId(), r.atin(), r.productTitle(),
                r.sellerOrgId(), r.customerId(), r.reviewerName(), r.rating(), r.content().title(),
                r.content().text(), r.content().photos().stream().map(ReviewPhoto::url).toList(), r.status().name(),
                r.flags().stream().map(Enum::name).toList(), r.createdAt(), r.reportCount(), reportReason,
                m == null ? null : m.by(), m == null ? null : m.at(),
                m == null || m.reason() == null ? null : m.reason().name(), m == null ? null : m.note(),
                reply == null ? null : reply.text(), reply == null ? null : reply.at(), base + "/approve",
                base + "/reject", base + "/remove");
    }
}
