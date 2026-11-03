package domain

import (
	"net/http"
	"time"
)

// Verified-purchase product reviews (S324): screen shapes of contracts/openapi/gateway-bff.yaml (tag Reviews) and
// gateway-bff-seller.yaml (Reviews). A review belongs to the product page; only customers who received the product
// can write one, reviewers show as first name and last initial, and the seller replies once.

// Problem codes of the review routes.
const (
	// CodeReviewNotVerifiedPurchase (403): the shopper has no delivered order line for the product.
	CodeReviewNotVerifiedPurchase = "REVIEW_NOT_VERIFIED_PURCHASE"
	// CodeReviewAlreadyExists (409): one review per customer and product; edit the existing one.
	CodeReviewAlreadyExists = "REVIEW_ALREADY_EXISTS"
	// CodeReviewEditWindowClosed (409): reviews can be edited for 30 days.
	CodeReviewEditWindowClosed = "REVIEW_EDIT_WINDOW_CLOSED"
)

// Review limits (catalog enforces them too).
const (
	ReviewMaxTitle  = 120
	ReviewMaxText   = 4000
	ReviewMaxPhotos = 5
	ReviewMaxReply  = 1000
	// ReviewPhotoMaxBytes and ReviewPhotoPurpose: photos are public, moderated images of media-service's storefront purpose.
	ReviewPhotoMaxBytes = 8 << 20
	ReviewPhotoPurpose  = "storefront"
)

// ReviewPhotoContentTypes are the image types a review photo may have.
var ReviewPhotoContentTypes = []string{"image/jpeg", "image/png", "image/webp"}

// ReviewRating is a product's average over its published reviews, the count and how many gave 1, 2, 3, 4 and 5 stars.
type ReviewRating struct {
	Average   float64 `json:"average"`
	Count     int     `json:"count"`
	Breakdown []int   `json:"breakdown"`
}

// ReviewPhoto is a photo of a review (public https URL).
type ReviewPhoto struct {
	URL string `json:"url"`
}

// ReviewReply is the seller's single public reply.
type ReviewReply struct {
	Text string `json:"text"`
	At   string `json:"at"`
}

// PublicReview is a published review as shoppers see it: always a verified purchase, the reviewer by first name and
// last initial.
type PublicReview struct {
	ID               string        `json:"id"`
	ReviewerName     string        `json:"reviewerName"`
	Rating           int           `json:"rating"`
	Title            *string       `json:"title"`
	Text             *string       `json:"text"`
	Photos           []ReviewPhoto `json:"photos"`
	VerifiedPurchase bool          `json:"verifiedPurchase"`
	CreatedAt        string        `json:"createdAt"`
	Reply            *ReviewReply  `json:"reply"`
}

// ProductReviewsPage is one page of a product's published reviews with its rating.
type ProductReviewsPage struct {
	Rating ReviewRating   `json:"rating"`
	Items  []PublicReview `json:"items"`
	Total  int64          `json:"total"`
	Page   int            `json:"page"`
	Size   int            `json:"size"`
}

// ReviewQuery selects a page of the public list.
type ReviewQuery struct {
	// Sort is recent (default), highest or lowest.
	Sort string
	// Stars shows only reviews with this rating (0 = all).
	Stars int
	Page  int
	Size  int
}

// ReviewModeration says why a review was refused or removed.
type ReviewModeration struct {
	ReasonCode string  `json:"reasonCode"`
	Reason     string  `json:"reason"`
	Note       *string `json:"note"`
}

// OwnProductReview is the shopper's own review in any state.
type OwnProductReview struct {
	ID            string            `json:"id"`
	ReviewerName  string            `json:"reviewerName"`
	Rating        int               `json:"rating"`
	Title         *string           `json:"title"`
	Text          *string           `json:"text"`
	Photos        []ReviewPhoto     `json:"photos"`
	Status        string            `json:"status"`
	CreatedAt     string            `json:"createdAt"`
	UpdatedAt     string            `json:"updatedAt"`
	EditableUntil string            `json:"editableUntil"`
	Editable      bool              `json:"editable"`
	Moderation    *ReviewModeration `json:"moderation"`
	Reply         *ReviewReply      `json:"reply"`
}

// MyProductReview is what the shopper can do about a product: write a review (eligible) or edit the one they wrote.
type MyProductReview struct {
	Eligible bool `json:"eligible"`
	// Reason says why not: not-purchased (nothing delivered yet) or already-reviewed.
	Reason *string           `json:"reason"`
	Review *OwnProductReview `json:"review"`
}

// ReviewPhotoInput is a photo of a review being written: a new upload (assetId) or one the review already has (url).
type ReviewPhotoInput struct {
	AssetID string `json:"assetId"`
	URL     string `json:"url"`
}

// ReviewInput is what the shopper writes (or rewrites): stars, title, text and up to five photos.
type ReviewInput struct {
	Rating int                `json:"rating"`
	Title  string             `json:"title"`
	Text   string             `json:"text"`
	Photos []ReviewPhotoInput `json:"photos"`
}

// ReviewReportInput reports a published review.
type ReviewReportInput struct {
	ReasonCode string `json:"reasonCode"`
	Details    string `json:"details"`
}

// ReviewReportResult is created=false when the reporter had already reported the review.
type ReviewReportResult struct {
	Created bool `json:"created"`
}

// ReviewReportReasons are the reasons a review can be reported for.
var ReviewReportReasons = []string{"ABUSIVE", "PERSONAL_INFORMATION", "SPAM", "OFF_TOPIC", "CONFLICT_OF_INTEREST", "OTHER"}

// ReviewModerationReasons name a moderation reason code for the shopper.
var ReviewModerationReasons = map[string]string{
	"ABUSIVE":              "It contained insults, harassment or hate.",
	"PERSONAL_INFORMATION": "It shared personal information.",
	"SPAM":                 "It looked like advertising or spam.",
	"OFF_TOPIC":            "It was not about the product.",
	"CONFLICT_OF_INTEREST": "It was written by or for the seller.",
	"OTHER":                "It did not follow our review guidelines.",
}

// ReviewPhotoUpload is a signed direct upload for a review photo (PUT the file to Upload.URL with Upload.Headers).
type ReviewPhotoUpload struct {
	AssetID  string          `json:"assetId"`
	Upload   ResumeUploadURL `json:"upload"`
	MaxBytes int64           `json:"maxBytes"`
}

// ReviewPhotoAsset is a finalised photo: pass AssetID in the review's photos. Status READY means media published it;
// anything else means it is still being checked or was refused.
type ReviewPhotoAsset struct {
	AssetID string  `json:"assetId"`
	URL     *string `json:"url"`
	Status  string  `json:"status"`
}

// SellerReview is a published review of the organisation's product (Seller Center), without the customer.
type SellerReview struct {
	ID               string        `json:"id"`
	ATIN             string        `json:"atin"`
	ProductTitle     string        `json:"productTitle"`
	ReviewerName     string        `json:"reviewerName"`
	Rating           int           `json:"rating"`
	Title            *string       `json:"title"`
	Text             *string       `json:"text"`
	Photos           []ReviewPhoto `json:"photos"`
	VerifiedPurchase bool          `json:"verifiedPurchase"`
	CreatedAt        string        `json:"createdAt"`
	ReportCount      int           `json:"reportCount"`
	Reply            *ReviewReply  `json:"reply"`
}

// SellerReviewsPage is one page of the organisation's published reviews.
type SellerReviewsPage struct {
	Items []SellerReview `json:"items"`
	Total int64          `json:"total"`
	Page  int            `json:"page"`
	Size  int            `json:"size"`
}

// SellerReviewQuery selects a page of the seller's reviews.
type SellerReviewQuery struct {
	// Unanswered lists only the reviews without a reply.
	Unanswered bool
	Page       int
	Size       int
}

// ReviewNotFound is the 404 of an unknown review.
func ReviewNotFound() *Problem {
	return NewProblem(http.StatusNotFound, CodeNotFound, "Review not found", "We couldn't find that review.")
}

// ReviewTime formats an upstream instant for the screens.
func ReviewTime(t time.Time) string { return t.UTC().Format(time.RFC3339) }
