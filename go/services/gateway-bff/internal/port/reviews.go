package port

import (
	"context"
	"time"
)

// ---------------------------------------------------------------- catalog: verified-purchase product reviews (S324)

// UpRating is catalog's ProductRating: the average over published reviews, the count and the 1..5 star breakdown.
type UpRating struct {
	Average   float64 `json:"average"`
	Count     int     `json:"count"`
	Breakdown []int   `json:"breakdown"`
}

// UpReviewPhoto is catalog's ReviewPhoto.
type UpReviewPhoto struct {
	URL string `json:"url"`
}

// UpReviewReply is catalog's ReviewReply: the seller's single reply.
type UpReviewReply struct {
	Text string    `json:"text"`
	At   time.Time `json:"at"`
}

// UpPublicReview is catalog's PublicReview.
type UpPublicReview struct {
	ID               string          `json:"id"`
	ReviewerName     string          `json:"reviewerName"`
	Rating           int             `json:"rating"`
	Title            *string         `json:"title"`
	Text             *string         `json:"text"`
	Photos           []UpReviewPhoto `json:"photos"`
	VerifiedPurchase bool            `json:"verifiedPurchase"`
	CreatedAt        time.Time       `json:"createdAt"`
	Reply            *UpReviewReply  `json:"reply"`
}

// UpPublicReviewPage is catalog's PublicReviewPage.
type UpPublicReviewPage struct {
	Rating UpRating         `json:"rating"`
	Items  []UpPublicReview `json:"items"`
	Total  int64            `json:"total"`
	Page   int              `json:"page"`
	Size   int              `json:"size"`
}

// UpReviewModeration is catalog's ReviewModeration: why a review was refused or removed.
type UpReviewModeration struct {
	ReasonCode *string `json:"reasonCode"`
	Note       *string `json:"note"`
}

// UpOwnReview is catalog's OwnReview: the customer's own review in any state.
type UpOwnReview struct {
	ID            string              `json:"id"`
	ATIN          string              `json:"atin"`
	ProductTitle  string              `json:"productTitle"`
	ReviewerName  string              `json:"reviewerName"`
	Rating        int                 `json:"rating"`
	Title         *string             `json:"title"`
	Text          *string             `json:"text"`
	Photos        []UpReviewPhoto     `json:"photos"`
	Status        string              `json:"status"`
	CreatedAt     time.Time           `json:"createdAt"`
	UpdatedAt     time.Time           `json:"updatedAt"`
	EditableUntil time.Time           `json:"editableUntil"`
	Editable      bool                `json:"editable"`
	Moderation    *UpReviewModeration `json:"moderation"`
	Reply         *UpReviewReply      `json:"reply"`
}

// UpMyReview is catalog's MyReview.
type UpMyReview struct {
	Eligible bool         `json:"eligible"`
	Reason   *string      `json:"reason"`
	Review   *UpOwnReview `json:"review"`
}

// UpReviewDraft is catalog's WriteReviewRequest / EditReviewRequest.
type UpReviewDraft struct {
	ReviewerName string          `json:"reviewerName,omitempty"`
	Rating       int             `json:"rating"`
	Title        string          `json:"title,omitempty"`
	Text         string          `json:"text,omitempty"`
	Photos       []UpReviewPhoto `json:"photos"`
}

// UpReportResult is catalog's ReviewReportResult.
type UpReportResult struct {
	Created bool `json:"created"`
	Reports int  `json:"reports"`
}

// UpSellerReview is catalog's SellerReview: a published review of the organisation's product, without the customer.
type UpSellerReview struct {
	ID               string          `json:"id"`
	ATIN             string          `json:"atin"`
	ProductTitle     string          `json:"productTitle"`
	ReviewerName     string          `json:"reviewerName"`
	Rating           int             `json:"rating"`
	Title            *string         `json:"title"`
	Text             *string         `json:"text"`
	Photos           []UpReviewPhoto `json:"photos"`
	VerifiedPurchase bool            `json:"verifiedPurchase"`
	CreatedAt        time.Time       `json:"createdAt"`
	ReportCount      int             `json:"reportCount"`
	Reply            *UpReviewReply  `json:"reply"`
}

// UpSellerReviewPage is catalog's SellerReviewPage.
type UpSellerReviewPage struct {
	Items []UpSellerReview `json:"items"`
	Total int64            `json:"total"`
	Page  int              `json:"page"`
	Size  int              `json:"size"`
}

// ReviewsClient is catalog's product-review API (contracts/openapi/catalog.yaml). The public page needs no token; the
// shopper's calls carry their own (catalog checks with orders that they received the product); the seller's carry the
// member's.
type ReviewsClient interface {
	PublicPage(ctx context.Context, atin string, stars int, sort string, page, size int) (UpPublicReviewPage, error)
	Mine(ctx context.Context, bearer, atin string) (UpMyReview, error)
	Write(ctx context.Context, bearer, atin string, in UpReviewDraft) (UpOwnReview, error)
	Edit(ctx context.Context, bearer, atin string, in UpReviewDraft) (UpOwnReview, error)
	Delete(ctx context.Context, bearer, atin string) error
	// Report reports a published review: a shopper, or a member of the product's seller.
	Report(ctx context.Context, bearer, reviewID, reasonCode, details string) (UpReportResult, error)
	SellerList(ctx context.Context, bearer string, unanswered bool, page, size int) (UpSellerReviewPage, error)
	Reply(ctx context.Context, bearer, reviewID, text string) (UpSellerReview, error)
}
