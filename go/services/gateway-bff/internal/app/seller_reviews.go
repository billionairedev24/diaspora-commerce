package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerReviewsService is Reviews in Seller Center (S324): the published, verified-purchase reviews of the
// organisation's products, newest first, with the customer's identity left out. The seller replies once to each review
// and can report one; they cannot edit, hide or remove a review (only Trust & Safety can).
type SellerReviewsService interface {
	List(ctx context.Context, c *domain.Caller, q domain.SellerReviewQuery) (domain.SellerReviewsPage, error)
	// Reply publishes the seller's single reply (409 ALREADY_DONE the second time).
	Reply(ctx context.Context, c *domain.Caller, reviewID, text string) (domain.SellerReview, error)
	Report(ctx context.Context, c *domain.Caller, reviewID string, in domain.ReviewReportInput) (domain.ReviewReportResult, error)
}

type sellerReviewsImpl struct {
	r *orgResolver
}

var _ SellerReviewsService = (*sellerReviewsImpl)(nil)

// NewSellerReviewsService returns the SellerReviewsService; d.Reviews is catalog's review API (nil answers 503).
func NewSellerReviewsService(d SellerDeps) SellerReviewsService {
	return &sellerReviewsImpl{r: newResolver(d)}
}

func (s *sellerReviewsImpl) List(ctx context.Context, c *domain.Caller, q domain.SellerReviewQuery) (domain.SellerReviewsPage, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "reviews", domain.LevelView); err != nil {
		return domain.SellerReviewsPage{}, err
	}
	if s.r.d.Reviews == nil {
		return domain.SellerReviewsPage{}, domain.UpstreamUnavailable("catalog")
	}
	if q.Page < 0 || q.Size < 0 || q.Size > 100 {
		return domain.SellerReviewsPage{}, domain.ValidationProblem(map[string]string{"size": "Use a page of 1 to 100"})
	}
	if q.Size == 0 {
		q.Size = 20
	}
	up, err := s.r.d.Reviews.SellerList(ctx, c.BearerToken, q.Unanswered, q.Page, q.Size)
	if err != nil {
		return domain.SellerReviewsPage{}, mapReviewErr(err)
	}
	out := domain.SellerReviewsPage{Items: []domain.SellerReview{}, Total: up.Total, Page: up.Page, Size: up.Size}
	for _, r := range up.Items {
		out.Items = append(out.Items, toSellerReview(r))
	}
	return out, nil
}

func (s *sellerReviewsImpl) Reply(ctx context.Context, c *domain.Caller, reviewID, text string) (domain.SellerReview, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "reviews", domain.LevelEdit); err != nil {
		return domain.SellerReview{}, err
	}
	if s.r.d.Reviews == nil {
		return domain.SellerReview{}, domain.UpstreamUnavailable("catalog")
	}
	text = strings.TrimSpace(text)
	if text == "" || len([]rune(text)) > domain.ReviewMaxReply {
		return domain.SellerReview{}, domain.ValidationProblem(map[string]string{"text": "Write 1 to 1,000 characters"})
	}
	r, err := s.r.d.Reviews.Reply(ctx, c.BearerToken, reviewID, text)
	if err != nil {
		return domain.SellerReview{}, mapReviewErr(err)
	}
	return toSellerReview(r), nil
}

func (s *sellerReviewsImpl) Report(ctx context.Context, c *domain.Caller, reviewID string, in domain.ReviewReportInput) (domain.ReviewReportResult, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "reviews", domain.LevelEdit); err != nil {
		return domain.ReviewReportResult{}, err
	}
	if s.r.d.Reviews == nil {
		return domain.ReviewReportResult{}, domain.UpstreamUnavailable("catalog")
	}
	reason, details, err := validateReport(in)
	if err != nil {
		return domain.ReviewReportResult{}, err
	}
	r, err := s.r.d.Reviews.Report(ctx, c.BearerToken, reviewID, reason, details)
	if err != nil {
		return domain.ReviewReportResult{}, mapReviewErr(err)
	}
	return domain.ReviewReportResult{Created: r.Created}, nil
}

func toSellerReview(r port.UpSellerReview) domain.SellerReview {
	return domain.SellerReview{ID: r.ID, ATIN: r.ATIN, ProductTitle: r.ProductTitle, ReviewerName: r.ReviewerName, Rating: r.Rating,
		Title: r.Title, Text: r.Text, Photos: toReviewPhotos(r.Photos), VerifiedPurchase: true, CreatedAt: domain.ReviewTime(r.CreatedAt),
		ReportCount: r.ReportCount, Reply: toReviewReply(r.Reply)}
}
