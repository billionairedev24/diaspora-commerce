package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerReviews mounts the storefront's verified-purchase product reviews (S324, tag Reviews): the public list and
// rating of a product, the shopper's own review (write, edit within 30 days, delete), reports, and the signed uploads
// of review photos. Whether a shopper may review is decided by catalog with orders-service (a delivered order line).
func (h *handlersImpl) registerReviews(route func(pattern string, fn http.HandlerFunc)) {
	lookup := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("lookup", h.o.LookupPerMinute, fn) }
	route("GET /v1/products/{atin}/reviews", h.productReviews)
	route("GET /v1/products/{atin}/reviews/mine", h.myProductReview)
	route("POST /v1/products/{atin}/reviews", h.writeProductReview)
	route("PUT /v1/products/{atin}/reviews/mine", h.editProductReview)
	route("DELETE /v1/products/{atin}/reviews/mine", h.deleteProductReview)
	route("POST /v1/reviews/{reviewId}/reports", lookup(h.reportReview))
	route("POST /v1/review-photos/uploads", lookup(h.reviewPhotoUpload))
	route("POST /v1/review-photos/uploads/{assetId}/complete", lookup(h.reviewPhotoComplete))
}

func (h *handlersImpl) productReviews(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	query := domain.ReviewQuery{Sort: q.Get("sort")}
	errs := map[string]string{}
	for key, dst := range map[string]*int{"stars": &query.Stars, "page": &query.Page, "size": &query.Size} {
		if v := q.Get(key); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil {
				errs[key] = "Must be a number"
				continue
			}
			*dst = n
		}
	}
	if len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	v, err := h.s.Shopper.Reviews.Page(r.Context(), r.PathValue("atin"), query)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) myProductReview(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Reviews.Mine(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) writeProductReview(w http.ResponseWriter, r *http.Request) {
	var in domain.ReviewInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Reviews.Write(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), in)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) editProductReview(w http.ResponseWriter, r *http.Request) {
	var in domain.ReviewInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Reviews.Edit(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), in)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) deleteProductReview(w http.ResponseWriter, r *http.Request) {
	err := h.s.Shopper.Reviews.Delete(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) reportReview(w http.ResponseWriter, r *http.Request) {
	var in domain.ReviewReportInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Reviews.Report(r.Context(), callerFrom(r.Context()), r.PathValue("reviewId"), in)
	status := http.StatusOK
	if err == nil && v.Created {
		status = http.StatusCreated
	}
	h.reply(w, r, status, v, err)
}

func (h *handlersImpl) reviewPhotoUpload(w http.ResponseWriter, r *http.Request) {
	var b struct {
		FileName    string `json:"fileName"`
		ContentType string `json:"contentType"`
		Size        int64  `json:"size"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Reviews.PhotoUpload(r.Context(), callerFrom(r.Context()), b.FileName, b.ContentType, b.Size)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) reviewPhotoComplete(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Reviews.CompletePhoto(r.Context(), callerFrom(r.Context()), r.PathValue("assetId"))
	h.ok(w, r, v, err)
}

// ---------------------------------------------------------------- Seller Center

// registerSellerReviews mounts Seller Center's Reviews (S324): the organisation's published reviews, one reply each, and
// reports. There is deliberately no route to edit, hide or delete a review.
func (h *handlersImpl) registerSellerReviews(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/seller/reviews", h.sellerReviews)
	route("POST /v1/seller/reviews/{reviewId}/reply", h.sellerReviewReply)
	route("POST /v1/seller/reviews/{reviewId}/reports", h.sellerReviewReport)
}

func (h *handlersImpl) sellerReviews(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	query := domain.SellerReviewQuery{Unanswered: q.Get("unanswered") == "true"}
	errs := map[string]string{}
	for key, dst := range map[string]*int{"page": &query.Page, "size": &query.Size} {
		if v := q.Get(key); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil {
				errs[key] = "Must be a number"
				continue
			}
			*dst = n
		}
	}
	if len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	v, err := h.s.SellerReviews.List(r.Context(), callerFrom(r.Context()), query)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) sellerReviewReply(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Text string `json:"text"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerReviews.Reply(r.Context(), callerFrom(r.Context()), r.PathValue("reviewId"), b.Text)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) sellerReviewReport(w http.ResponseWriter, r *http.Request) {
	var in domain.ReviewReportInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerReviews.Report(r.Context(), callerFrom(r.Context()), r.PathValue("reviewId"), in)
	status := http.StatusOK
	if err == nil && v.Created {
		status = http.StatusCreated
	}
	h.reply(w, r, status, v, err)
}
