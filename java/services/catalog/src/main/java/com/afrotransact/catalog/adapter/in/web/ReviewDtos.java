package com.afrotransact.catalog.adapter.in.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ProductReviewService.MyReview;
import com.afrotransact.catalog.application.ProductReviewService.PublicReviews;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewDraft;
import com.afrotransact.catalog.domain.ReviewPhoto;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the product review endpoints (S324). */
final class ReviewDtos {

    private ReviewDtos() {
    }

    @Schema(name = "ReviewPhoto", description = "A review photo: the public https URL media-service gave the "
            + "uploaded, moderated image")
    record PhotoDto(@NotBlank @Size(max = ReviewPhoto.MAX_URL) String url) {

        static PhotoDto of(ReviewPhoto p) {
            return new PhotoDto(p.url());
        }

        ReviewPhoto toDomain() {
            return new ReviewPhoto(url);
        }
    }

    @Schema(name = "WriteReviewRequest", description = "Stars are required; title, text and up to 5 photos are "
            + "optional. reviewerName is the shopper's account name: only the first name and last initial are kept")
    record WriteReviewRequest(@Size(max = 200) String reviewerName, int rating, String title, String text,
                              @Size(max = 20) List<@Valid PhotoDto> photos) {

        ReviewDraft toDraft() {
            return new ReviewDraft(rating, title, text, photos == null ? List.of()
                    : photos.stream().map(PhotoDto::toDomain).toList());
        }
    }

    @Schema(name = "EditReviewRequest", description = "The whole review again (stars, title, text, photos)")
    record EditReviewRequest(int rating, String title, String text, @Size(max = 20) List<@Valid PhotoDto> photos) {

        ReviewDraft toDraft() {
            return new ReviewDraft(rating, title, text, photos == null ? List.of()
                    : photos.stream().map(PhotoDto::toDomain).toList());
        }
    }

    @Schema(name = "ReviewReportRequest", description = "Report a published review")
    record ReportReviewRequest(@NotNull ReviewReasonCode reasonCode, @Size(max = 500) String details) {
    }

    @Schema(name = "ReviewReportResult")
    record ReportResultResponse(boolean created, int reports) {
    }

    @Schema(name = "ReviewReplyRequest", description = "The product owner's single public reply")
    record ReplyRequest(@NotBlank @Size(max = ProductReview.MAX_REPLY) String text) {
    }

    @Schema(name = "ReviewApproveRequest")
    record ApproveReviewRequest(@Size(max = ProductReview.MAX_NOTE) String note) {
    }

    @Schema(name = "ReviewRemoveRequest", description = "reasonCode is required; note is required for OTHER")
    record RemoveReviewRequest(@NotNull ReviewReasonCode reasonCode, @Size(max = ProductReview.MAX_NOTE) String note) {
    }

    @Schema(name = "ProductRating", description = "Average stars over published reviews, the count and how many "
            + "reviews gave 1, 2, 3, 4 and 5 stars")
    record RatingResponse(BigDecimal average, int count,
                          @Schema(description = "Counts for 1, 2, 3, 4 and 5 stars, in that order")
                          List<Integer> breakdown) {

        static RatingResponse of(RatingSummary s) {
            return new RatingResponse(s.average(), s.count(), s.breakdown());
        }
    }

    @Schema(name = "ReviewReply")
    record ReplyDto(String text, Instant at) {
    }

    @Schema(name = "ReviewModeration", description = "The last moderation decision: why a review was refused or removed")
    record ModerationDto(String by, Instant at, ReviewReasonCode reasonCode, String note) {

        static ModerationDto of(ProductReview.Moderation m) {
            return m == null ? null : new ModerationDto(m.by(), m.at(), m.reason(), m.note());
        }
    }

    @Schema(name = "PublicReview", description = "A published review as shoppers see it: Verified purchase, the "
            + "reviewer's first name and last initial only")
    record PublicReviewResponse(UUID id, String reviewerName, int rating, String title, String text,
                                List<PhotoDto> photos, boolean verifiedPurchase, Instant createdAt, ReplyDto reply) {

        static PublicReviewResponse of(ProductReview r) {
            return new PublicReviewResponse(r.id(), r.reviewerName(), r.rating(), r.content().title(),
                    r.content().text(), photosOf(r), true, r.createdAt(), replyOf(r));
        }
    }

    @Schema(name = "PublicReviewPage", description = "One page of published reviews and the product's rating")
    record PublicReviewPageResponse(RatingResponse rating, List<PublicReviewResponse> items, long total, int page,
                                    int size) {

        static PublicReviewPageResponse of(PublicReviews r) {
            PageView<ProductReview> p = r.page();
            return new PublicReviewPageResponse(RatingResponse.of(r.rating()),
                    p.items().stream().map(PublicReviewResponse::of).toList(), p.total(), p.page(), p.size());
        }
    }

    @Schema(name = "OwnReview", description = "The customer's own review in any state; moderation says why it was "
            + "refused")
    record OwnReviewResponse(UUID id, String atin, String productTitle, String reviewerName, int rating, String title,
                             String text, List<PhotoDto> photos, ReviewStatus status, Instant createdAt,
                             Instant updatedAt, Instant editableUntil, boolean editable, ModerationDto moderation,
                             ReplyDto reply) {

        static OwnReviewResponse of(ProductReview r, Duration window, Instant now) {
            ModerationDto m = r.status().refused() ? ModerationDto.of(r.moderation()) : null;
            return new OwnReviewResponse(r.id(), r.atin(), r.productTitle(), r.reviewerName(), r.rating(),
                    r.content().title(), r.content().text(), photosOf(r), r.status(), r.createdAt(), r.updatedAt(),
                    r.editableUntil(window), r.editable(window, now), m, replyOf(r));
        }
    }

    @Schema(name = "MyReview", description = "What the signed-in customer can do about a product: eligible means they "
            + "received it and have not reviewed it yet; reason says why not (not-purchased, already-reviewed)")
    record MyReviewResponse(boolean eligible, String reason, OwnReviewResponse review) {

        static MyReviewResponse of(MyReview m, Duration window, Instant now) {
            return new MyReviewResponse(m.eligible(), m.reason(),
                    m.review() == null ? null : OwnReviewResponse.of(m.review(), window, now));
        }
    }

    @Schema(name = "SellerReview", description = "A published review of the organisation's product, with the "
            + "customer's identity left out")
    record SellerReviewResponse(UUID id, String atin, String productTitle, String reviewerName, int rating,
                                String title, String text, List<PhotoDto> photos, boolean verifiedPurchase,
                                Instant createdAt, int reportCount, ReplyDto reply) {

        static SellerReviewResponse of(ProductReview r) {
            return new SellerReviewResponse(r.id(), r.atin(), r.productTitle(), r.reviewerName(), r.rating(),
                    r.content().title(), r.content().text(), photosOf(r), true, r.createdAt(), r.reportCount(),
                    replyOf(r));
        }
    }

    @Schema(name = "SellerReviewPage")
    record SellerReviewPageResponse(List<SellerReviewResponse> items, long total, int page, int size) {

        static SellerReviewPageResponse of(PageView<ProductReview> p) {
            return new SellerReviewPageResponse(p.items().stream().map(SellerReviewResponse::of).toList(), p.total(),
                    p.page(), p.size());
        }
    }

    @Schema(name = "ProductReview", description = "A review for moderation: the author's id, the delivering order, "
            + "what the automatic checks found and the decision")
    record StaffReviewResponse(UUID id, UUID productId, String atin, String productTitle, String sellerOrgId,
                               String customerId, String reviewerName, String orderNumber, int rating, String title,
                               String text, List<PhotoDto> photos, ReviewStatus status, List<String> flags,
                               Instant createdAt, Instant updatedAt, int reportCount, ModerationDto moderation,
                               ReplyDto reply, long version) {

        static StaffReviewResponse of(ProductReview r) {
            return new StaffReviewResponse(r.id(), r.productId(), r.atin(), r.productTitle(), r.sellerOrgId(),
                    r.customerId(), r.reviewerName(), r.orderNumber(), r.rating(), r.content().title(),
                    r.content().text(), photosOf(r), r.status(), r.flags().stream().map(Enum::name).toList(),
                    r.createdAt(), r.updatedAt(), r.reportCount(), ModerationDto.of(r.moderation()), replyOf(r),
                    r.version());
        }
    }

    @Schema(name = "ProductReviewPage")
    record StaffReviewPageResponse(List<StaffReviewResponse> items, long total, int page, int size) {

        static StaffReviewPageResponse of(PageView<ProductReview> p) {
            return new StaffReviewPageResponse(p.items().stream().map(StaffReviewResponse::of).toList(), p.total(),
                    p.page(), p.size());
        }
    }

    private static List<PhotoDto> photosOf(ProductReview r) {
        return r.content().photos().stream().map(PhotoDto::of).toList();
    }

    private static ReplyDto replyOf(ProductReview r) {
        return r.reply() == null ? null : new ReplyDto(r.reply().text(), r.reply().at());
    }
}
