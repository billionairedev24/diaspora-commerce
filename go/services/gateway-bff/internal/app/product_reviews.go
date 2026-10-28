package app

import (
	"context"
	"errors"
	"net/http"
	"path"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ProductReviewService is the storefront's verified-purchase ratings and reviews (S324). Anyone reads a product's
// published reviews and rating; a signed-in shopper who received the product (catalog asks orders-service) reviews it
// once, edits it for 30 days, deletes it and reports other people's reviews. Photos go straight to media-service with
// signed uploads, like the careers résumé: the BFF signs, the browser uploads, the review carries the asset ids.
type ProductReviewService interface {
	// Page is one page of the product's published reviews with its rating (public).
	Page(ctx context.Context, atin string, q domain.ReviewQuery) (domain.ProductReviewsPage, error)
	// Mine says whether the shopper can review the product and returns their review in any state.
	Mine(ctx context.Context, c *domain.Caller, atin string) (domain.MyProductReview, error)
	Write(ctx context.Context, c *domain.Caller, atin string, in domain.ReviewInput) (domain.OwnProductReview, error)
	Edit(ctx context.Context, c *domain.Caller, atin string, in domain.ReviewInput) (domain.OwnProductReview, error)
	Delete(ctx context.Context, c *domain.Caller, atin string) error
	Report(ctx context.Context, c *domain.Caller, reviewID string, in domain.ReviewReportInput) (domain.ReviewReportResult, error)
	// PhotoUpload signs a direct upload for one review photo; CompletePhoto finalises it.
	PhotoUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.ReviewPhotoUpload, error)
	CompletePhoto(ctx context.Context, c *domain.Caller, assetID string) (domain.ReviewPhotoAsset, error)
}

type productReviewServiceImpl struct {
	reviews port.ReviewsClient
	media   port.MediaClient
	account port.AccountSettingsClient
}

var _ ProductReviewService = (*productReviewServiceImpl)(nil)

// NewProductReviewService returns the ProductReviewService. account (optional) names the reviewer when the session
// has no profile (the customer app).
func NewProductReviewService(reviews port.ReviewsClient, media port.MediaClient, account port.AccountSettingsClient) ProductReviewService {
	return &productReviewServiceImpl{reviews: reviews, media: media, account: account}
}

// reviewSorts maps the screen's sort names to catalog's.
var reviewSorts = map[string]string{"": "NEWEST", "recent": "NEWEST", "highest": "HIGHEST", "lowest": "LOWEST"}

func (s *productReviewServiceImpl) Page(ctx context.Context, atin string, q domain.ReviewQuery) (domain.ProductReviewsPage, error) {
	atin, err := reviewATIN(atin)
	if err != nil {
		return domain.ProductReviewsPage{}, err
	}
	sort, ok := reviewSorts[strings.ToLower(q.Sort)]
	errs := map[string]string{}
	if !ok {
		errs["sort"] = "Use recent, highest or lowest"
	}
	if q.Stars < 0 || q.Stars > 5 {
		errs["stars"] = "Use 1 to 5"
	}
	if q.Page < 0 {
		errs["page"] = "Must be 0 or more"
	}
	if q.Size < 0 || q.Size > 50 {
		errs["size"] = "Use 1 to 50"
	}
	if len(errs) > 0 {
		return domain.ProductReviewsPage{}, domain.ValidationProblem(errs)
	}
	if q.Size == 0 {
		q.Size = 10
	}
	up, err := s.reviews.PublicPage(ctx, atin, q.Stars, sort, q.Page, q.Size)
	if err != nil {
		return domain.ProductReviewsPage{}, mapReviewErr(err)
	}
	out := domain.ProductReviewsPage{Rating: toReviewRating(up.Rating), Items: []domain.PublicReview{}, Total: up.Total, Page: up.Page, Size: up.Size}
	for _, r := range up.Items {
		out.Items = append(out.Items, domain.PublicReview{ID: r.ID, ReviewerName: r.ReviewerName, Rating: r.Rating, Title: r.Title, Text: r.Text,
			Photos: toReviewPhotos(r.Photos), VerifiedPurchase: true, CreatedAt: domain.ReviewTime(r.CreatedAt), Reply: toReviewReply(r.Reply)})
	}
	return out, nil
}

func (s *productReviewServiceImpl) Mine(ctx context.Context, c *domain.Caller, atin string) (domain.MyProductReview, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MyProductReview{}, err
	}
	atin, err := reviewATIN(atin)
	if err != nil {
		return domain.MyProductReview{}, err
	}
	m, err := s.reviews.Mine(ctx, c.BearerToken, atin)
	if err != nil {
		return domain.MyProductReview{}, mapReviewErr(err)
	}
	out := domain.MyProductReview{Eligible: m.Eligible, Reason: m.Reason}
	if m.Review != nil {
		own := toOwnReview(*m.Review)
		out.Review = &own
	}
	return out, nil
}

func (s *productReviewServiceImpl) Write(ctx context.Context, c *domain.Caller, atin string, in domain.ReviewInput) (domain.OwnProductReview, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OwnProductReview{}, err
	}
	atin, err := reviewATIN(atin)
	if err != nil {
		return domain.OwnProductReview{}, err
	}
	if err := validateReview(in); err != nil {
		return domain.OwnProductReview{}, err
	}
	photos, err := s.photos(ctx, c, atin, in.Photos, false)
	if err != nil {
		return domain.OwnProductReview{}, err
	}
	r, err := s.reviews.Write(ctx, c.BearerToken, atin, port.UpReviewDraft{ReviewerName: s.reviewerName(ctx, c), Rating: in.Rating,
		Title: strings.TrimSpace(in.Title), Text: strings.TrimSpace(in.Text), Photos: photos})
	if err != nil {
		return domain.OwnProductReview{}, mapReviewErr(err)
	}
	return toOwnReview(r), nil
}

func (s *productReviewServiceImpl) Edit(ctx context.Context, c *domain.Caller, atin string, in domain.ReviewInput) (domain.OwnProductReview, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OwnProductReview{}, err
	}
	atin, err := reviewATIN(atin)
	if err != nil {
		return domain.OwnProductReview{}, err
	}
	if err := validateReview(in); err != nil {
		return domain.OwnProductReview{}, err
	}
	photos, err := s.photos(ctx, c, atin, in.Photos, true)
	if err != nil {
		return domain.OwnProductReview{}, err
	}
	r, err := s.reviews.Edit(ctx, c.BearerToken, atin, port.UpReviewDraft{Rating: in.Rating, Title: strings.TrimSpace(in.Title),
		Text: strings.TrimSpace(in.Text), Photos: photos})
	if err != nil {
		return domain.OwnProductReview{}, mapReviewErr(err)
	}
	return toOwnReview(r), nil
}

func (s *productReviewServiceImpl) Delete(ctx context.Context, c *domain.Caller, atin string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	atin, err := reviewATIN(atin)
	if err != nil {
		return err
	}
	return mapReviewErr(s.reviews.Delete(ctx, c.BearerToken, atin))
}

func (s *productReviewServiceImpl) Report(ctx context.Context, c *domain.Caller, reviewID string, in domain.ReviewReportInput) (domain.ReviewReportResult, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReviewReportResult{}, err
	}
	reason, details, err := validateReport(in)
	if err != nil {
		return domain.ReviewReportResult{}, err
	}
	r, err := s.reviews.Report(ctx, c.BearerToken, reviewID, reason, details)
	if err != nil {
		return domain.ReviewReportResult{}, mapReviewErr(err)
	}
	return domain.ReviewReportResult{Created: r.Created}, nil
}

func (s *productReviewServiceImpl) PhotoUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.ReviewPhotoUpload, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReviewPhotoUpload{}, err
	}
	fileName = strings.TrimSpace(path.Base(strings.ReplaceAll(fileName, "\\", "/")))
	errs := map[string]string{}
	if fileName == "" || fileName == "." || len(fileName) > 200 {
		errs["fileName"] = "Choose a photo"
	}
	if !slices.Contains(domain.ReviewPhotoContentTypes, strings.ToLower(contentType)) {
		errs["contentType"] = "Photos are JPEG, PNG or WebP"
	}
	if size <= 0 || size > domain.ReviewPhotoMaxBytes {
		errs["size"] = "A photo must be 8 MB or smaller"
	}
	if len(errs) > 0 {
		return domain.ReviewPhotoUpload{}, domain.ValidationProblem(errs)
	}
	up, err := s.media.RequestUpload(ctx, c.BearerToken, domain.ReviewPhotoPurpose, fileName, strings.ToLower(contentType), size)
	if err != nil {
		return domain.ReviewPhotoUpload{}, mapErr("media", err)
	}
	headers := up.Upload.Headers
	if headers == nil {
		headers = map[string]string{"Content-Type": strings.ToLower(contentType)}
	}
	return domain.ReviewPhotoUpload{AssetID: up.Asset.ID, MaxBytes: domain.ReviewPhotoMaxBytes, Upload: domain.ResumeUploadURL{
		Method: firstNonEmpty(up.Upload.Method, http.MethodPut), URL: up.Upload.URL, Headers: headers, ExpiresAt: up.Upload.ExpiresAt}}, nil
}

func (s *productReviewServiceImpl) CompletePhoto(ctx context.Context, c *domain.Caller, assetID string) (domain.ReviewPhotoAsset, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReviewPhotoAsset{}, err
	}
	a, err := s.media.Complete(ctx, c.BearerToken, assetID)
	if err != nil {
		return domain.ReviewPhotoAsset{}, mapErr("media", err)
	}
	return domain.ReviewPhotoAsset{AssetID: a.ID, URL: optStr(a.URL), Status: a.Status}, nil
}

// photos turns the review's photo inputs into public URLs. A new upload must be the shopper's own, published asset of
// the review-photo purpose; a photo given by URL must already be on the shopper's review (edits keep them), so the
// BFF never passes on a link the shopper made up.
func (s *productReviewServiceImpl) photos(ctx context.Context, c *domain.Caller, atin string, in []domain.ReviewPhotoInput, editing bool) ([]port.UpReviewPhoto, error) {
	out := make([]port.UpReviewPhoto, 0, len(in))
	var current map[string]bool
	for _, p := range in {
		switch {
		case p.AssetID != "":
			a, err := s.media.Asset(ctx, c.BearerToken, p.AssetID)
			if err != nil {
				if errors.Is(err, port.ErrNotFound) {
					return nil, domain.ValidationProblem(map[string]string{"photos": "A photo is no longer available. Remove it and add it again."})
				}
				return nil, mapErr("media", err)
			}
			if a.Status != "READY" || strings.TrimSpace(a.URL) == "" || (a.Purpose != "" && a.Purpose != domain.ReviewPhotoPurpose) {
				return nil, domain.ValidationProblem(map[string]string{"photos": "A photo is still being checked or was not accepted. Remove it and try again."})
			}
			out = append(out, port.UpReviewPhoto{URL: a.URL})
		case p.URL != "":
			if current == nil {
				current = map[string]bool{}
				if editing {
					m, err := s.reviews.Mine(ctx, c.BearerToken, atin)
					if err != nil {
						return nil, mapReviewErr(err)
					}
					if m.Review != nil {
						for _, ph := range m.Review.Photos {
							current[ph.URL] = true
						}
					}
				}
			}
			if !current[p.URL] {
				return nil, domain.ValidationProblem(map[string]string{"photos": "Upload photos again to add them to your review."})
			}
			out = append(out, port.UpReviewPhoto{URL: p.URL})
		default:
			return nil, domain.ValidationProblem(map[string]string{"photos": "Each photo needs an upload."})
		}
	}
	return slices.CompactFunc(out, func(a, b port.UpReviewPhoto) bool { return a.URL == b.URL }), nil
}

// reviewerName is the shopper's account name; catalog publishes only the first name and last initial.
func (s *productReviewServiceImpl) reviewerName(ctx context.Context, c *domain.Caller) string {
	if c.Session != nil && c.Session.Profile != nil {
		if n := strings.TrimSpace(c.Session.Profile.FirstName + " " + c.Session.Profile.LastName); n != "" {
			return n
		}
	}
	if s.account != nil {
		if p, err := s.account.Profile(ctx, c.BearerToken); err == nil {
			return strings.TrimSpace(p.FirstName + " " + p.LastName)
		}
	}
	return ""
}

func reviewATIN(raw string) (string, error) {
	atin := strings.ToUpper(strings.TrimSpace(raw))
	if !atinRe.MatchString(atin) {
		return "", productNotFound(atin)
	}
	return atin, nil
}

func validateReview(in domain.ReviewInput) error {
	errs := map[string]string{}
	if in.Rating < 1 || in.Rating > 5 {
		errs["rating"] = "Choose 1 to 5 stars"
	}
	if n := len([]rune(strings.TrimSpace(in.Title))); n > domain.ReviewMaxTitle {
		errs["title"] = "At most 120 characters"
	}
	if n := len([]rune(strings.TrimSpace(in.Text))); n > domain.ReviewMaxText {
		errs["text"] = "At most 4,000 characters"
	}
	if len(in.Photos) > domain.ReviewMaxPhotos {
		errs["photos"] = "Up to 5 photos"
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	return nil
}

func validateReport(in domain.ReviewReportInput) (reason, details string, err error) {
	reason = strings.ToUpper(strings.TrimSpace(in.ReasonCode))
	details = strings.TrimSpace(in.Details)
	errs := map[string]string{}
	if !slices.Contains(domain.ReviewReportReasons, reason) {
		errs["reasonCode"] = "Choose why you are reporting this review"
	}
	if reason == "OTHER" && details == "" {
		errs["details"] = "Tell us what is wrong"
	}
	if len([]rune(details)) > 500 {
		errs["details"] = "At most 500 characters"
	}
	if len(errs) > 0 {
		return "", "", domain.ValidationProblem(errs)
	}
	return reason, details, nil
}

func toReviewRating(r port.UpRating) domain.ReviewRating {
	b := r.Breakdown
	if len(b) != 5 {
		b = []int{0, 0, 0, 0, 0}
	}
	return domain.ReviewRating{Average: r.Average, Count: r.Count, Breakdown: b}
}

func toReviewPhotos(in []port.UpReviewPhoto) []domain.ReviewPhoto {
	out := make([]domain.ReviewPhoto, 0, len(in))
	for _, p := range in {
		out = append(out, domain.ReviewPhoto{URL: p.URL})
	}
	return out
}

func toReviewReply(r *port.UpReviewReply) *domain.ReviewReply {
	if r == nil {
		return nil
	}
	return &domain.ReviewReply{Text: r.Text, At: domain.ReviewTime(r.At)}
}

func toOwnReview(r port.UpOwnReview) domain.OwnProductReview {
	out := domain.OwnProductReview{ID: r.ID, ReviewerName: r.ReviewerName, Rating: r.Rating, Title: r.Title, Text: r.Text,
		Photos: toReviewPhotos(r.Photos), Status: r.Status, CreatedAt: domain.ReviewTime(r.CreatedAt), UpdatedAt: domain.ReviewTime(r.UpdatedAt),
		EditableUntil: domain.ReviewTime(r.EditableUntil), Editable: r.Editable, Reply: toReviewReply(r.Reply)}
	if m := r.Moderation; m != nil && m.ReasonCode != nil {
		out.Moderation = &domain.ReviewModeration{ReasonCode: *m.ReasonCode, Reason: firstNonEmpty(domain.ReviewModerationReasons[*m.ReasonCode],
			domain.ReviewModerationReasons["OTHER"]), Note: m.Note}
	}
	return out
}

// mapReviewErr turns catalog's review refusals into the messages shoppers and sellers see.
func mapReviewErr(err error) error {
	if err == nil {
		return nil
	}
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusForbidden && p.Code == "catalog.review-not-verified-purchase":
			return domain.NewProblem(http.StatusForbidden, domain.CodeReviewNotVerifiedPurchase, "Only for items you received",
				"You can review an item once it has been delivered to you.").WithCause(err)
		case p.Status == http.StatusForbidden && p.Code == "catalog.review-own-report":
			return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Your own review", "You can't report your own review.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "catalog.review-already-exists":
			return domain.NewProblem(http.StatusConflict, domain.CodeReviewAlreadyExists, "You already reviewed this item",
				"Edit your review instead.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "catalog.review-edit-window-closed":
			return domain.NewProblem(http.StatusConflict, domain.CodeReviewEditWindowClosed, "Reviews can't be edited any more",
				"Reviews can be edited for 30 days after you wrote them. You can still delete yours.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "catalog.review-already-replied":
			return domain.NewProblem(http.StatusConflict, domain.CodeAlreadyDone, "Already replied", "You have already replied to this review.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "catalog.review-not-published":
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Not published yet", "You can reply once the review is published.").WithCause(err)
		case p.Status == http.StatusNotFound && p.Code == "catalog.review-not-found":
			return domain.ReviewNotFound().WithCause(err)
		case p.Status == http.StatusNotFound && p.Code == "catalog.product-not-found":
			return domain.NewProblem(http.StatusNotFound, domain.CodeProductNotFound, "Product not found", "We couldn't find that product.").WithCause(err)
		case p.Status == http.StatusServiceUnavailable && p.Code == "catalog.purchase-check-unavailable":
			return domain.UpstreamUnavailable("orders").WithCause(err)
		}
	}
	return mapErr("catalog", err)
}
