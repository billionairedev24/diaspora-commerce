package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type reviewsImpl struct{ c httpclient.Client }

var _ port.ReviewsClient = (*reviewsImpl)(nil)

// NewReviews returns the catalog client for verified-purchase product reviews (S324).
func NewReviews(c httpclient.Client) port.ReviewsClient { return &reviewsImpl{c: c} }

func reviewsPath(atin string) string {
	return "/v1/products/" + url.PathEscape(strings.ToUpper(atin)) + "/reviews"
}

func (r *reviewsImpl) PublicPage(ctx context.Context, atin string, stars int, sort string, page, size int) (port.UpPublicReviewPage, error) {
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if stars > 0 {
		q.Set("stars", strconv.Itoa(stars))
	}
	if sort != "" {
		q.Set("sort", sort)
	}
	var out port.UpPublicReviewPage
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/public/products/" + url.PathEscape(strings.ToUpper(atin)) + "/reviews", Query: q}, &out)
	return out, err
}

func (r *reviewsImpl) Mine(ctx context.Context, bearer, atin string) (port.UpMyReview, error) {
	var out port.UpMyReview
	_, err := call(ctx, r.c, httpclient.Request{Path: reviewsPath(atin) + "/mine", Bearer: bearer}, &out)
	return out, err
}

func (r *reviewsImpl) Write(ctx context.Context, bearer, atin string, in port.UpReviewDraft) (port.UpOwnReview, error) {
	var out port.UpOwnReview
	// not retried: a lost answer must not become a second review (catalog would answer 409 anyway)
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPost, Path: reviewsPath(atin), Bearer: bearer, JSON: in}, &out)
	return out, err
}

func (r *reviewsImpl) Edit(ctx context.Context, bearer, atin string, in port.UpReviewDraft) (port.UpOwnReview, error) {
	var out port.UpOwnReview
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPut, Path: reviewsPath(atin) + "/mine", Bearer: bearer, JSON: in}, &out)
	return out, err
}

func (r *reviewsImpl) Delete(ctx context.Context, bearer, atin string) error {
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodDelete, Path: reviewsPath(atin) + "/mine", Bearer: bearer}, nil)
	return err
}

func (r *reviewsImpl) Report(ctx context.Context, bearer, reviewID, reasonCode, details string) (port.UpReportResult, error) {
	var out port.UpReportResult
	body := map[string]any{"reasonCode": reasonCode}
	if details != "" {
		body["details"] = details
	}
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/product-reviews/" + url.PathEscape(reviewID) + "/reports",
		Bearer: bearer, JSON: body}, &out)
	return out, err
}

func (r *reviewsImpl) SellerList(ctx context.Context, bearer string, unanswered bool, page, size int) (port.UpSellerReviewPage, error) {
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if unanswered {
		q.Set("unanswered", "true")
	}
	var out port.UpSellerReviewPage
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/seller/reviews", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (r *reviewsImpl) Reply(ctx context.Context, bearer, reviewID, text string) (port.UpSellerReview, error) {
	var out port.UpSellerReview
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/product-reviews/" + url.PathEscape(reviewID) + "/reply",
		Bearer: bearer, JSON: map[string]any{"text": text}}, &out)
	return out, err
}
