package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestProductReviewsConformAndEnforceTheRules drives every Reviews operation of the storefront contract (S324) through
// the real handlers and validates each answer against gateway-bff.yaml.
func TestProductReviewsConformAndEnforceTheRules(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	validated := validateAll(t, b)
	bought, notBought := testkit.ReviewBoughtATIN, testkit.ReviewNotBoughtATIN

	// Guests read, but cannot write, report or upload.
	r := b.do("GET", "/v1/products/"+bought+"/reviews", nil)
	mustStatus(t, r, 200)
	page := r.json(t)
	if rating := page["rating"].(map[string]any); rating["count"].(float64) != 2 {
		t.Fatalf("rating = %v", rating)
	}
	mustStatus(t, b.do("GET", "/v1/products/"+bought+"/reviews/mine", nil), 401)
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 5}), 401)
	mustStatus(t, b.do("POST", "/v1/reviews/"+testkit.ReviewSeededID+"/reports", map[string]any{"reasonCode": "SPAM"}), 401)
	mustStatus(t, b.do("POST", "/v1/review-photos/uploads", map[string]any{"fileName": "a.webp", "contentType": "image/webp", "size": 1000}), 401)

	// Sorting, star filter, paging and bad query values.
	items := b.do("GET", "/v1/products/"+bought+"/reviews?sort=LOWEST", nil).json(t)["items"].([]any)
	if len(items) != 2 || items[0].(map[string]any)["rating"].(float64) != 3 {
		t.Fatalf("lowest first = %v", items)
	}
	items = b.do("GET", "/v1/products/"+bought+"/reviews?sort=HIGHEST", nil).json(t)["items"].([]any)
	if items[0].(map[string]any)["rating"].(float64) != 5 {
		t.Fatalf("highest first = %v", items)
	}
	if got := b.do("GET", "/v1/products/"+bought+"/reviews?stars=3", nil).json(t)["total"].(float64); got != 1 {
		t.Fatalf("3-star total = %v", got)
	}
	paged := b.do("GET", "/v1/products/"+bought+"/reviews?size=1&page=1", nil).json(t)
	if len(paged["items"].([]any)) != 1 || paged["total"].(float64) != 2 {
		t.Fatalf("second page = %v", paged)
	}
	mustStatus(t, b.do("GET", "/v1/products/"+bought+"/reviews?stars=x", nil), 422)
	// Reviewers are shown by first name and last initial, never an id or e-mail.
	first := items[0].(map[string]any)
	if first["reviewerName"] != "Amara O." || first["verifiedPurchase"] != true {
		t.Fatalf("public review = %v", first)
	}

	mustStatus(t, signIn(t, bff, b, "/"), 302)

	// Eligibility: a delivered line is needed.
	me := b.do("GET", "/v1/products/"+bought+"/reviews/mine", nil).json(t)
	if me["eligible"] != true {
		t.Fatalf("mine = %v", me)
	}
	me = b.do("GET", "/v1/products/"+notBought+"/reviews/mine", nil).json(t)
	if me["eligible"] != false || me["reason"] != "not-purchased" {
		t.Fatalf("mine (not bought) = %v", me)
	}
	denied := b.do("POST", "/v1/products/"+notBought+"/reviews", map[string]any{"rating": 4, "title": "Nice"})
	mustStatus(t, denied, 403)
	if code := denied.json(t)["code"]; code != "REVIEW_NOT_VERIFIED_PURCHASE" {
		t.Fatalf("not verified code = %v", code)
	}
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 0}), 422)
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 5, "text": string(make([]byte, 4001))}), 422)

	// Photos: a ready upload is accepted, a held one and a forged URL are not.
	up := b.do("POST", "/v1/review-photos/uploads", map[string]any{"fileName": "jar.webp", "contentType": "image/webp", "size": 182000})
	mustStatus(t, up, 201)
	assetID := up.json(t)["assetId"].(string)
	done := b.do("POST", "/v1/review-photos/uploads/"+assetID+"/complete", nil)
	mustStatus(t, done, 200)
	if done.json(t)["url"] != testkit.ReviewPhotoURL {
		t.Fatalf("complete = %s", done.body)
	}
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 5, "photos": []any{map[string]any{"assetId": testkit.HeldAssetID}}}), 422)
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 5, "photos": []any{map[string]any{"url": "https://evil.example/x.jpg"}}}), 422)

	// Write: published at once, shown as the shopper's own review and in the rating.
	w := b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 4, "title": "Lovely", "text": "Soft and fragrant.",
		"photos": []any{map[string]any{"assetId": assetID}}})
	mustStatus(t, w, 201)
	own := w.json(t)
	if own["status"] != "PUBLISHED" || own["editable"] != true || len(own["photos"].([]any)) != 1 {
		t.Fatalf("own review = %v", own)
	}
	mustStatus(t, b.do("POST", "/v1/products/"+bought+"/reviews", map[string]any{"rating": 5}), 409)
	me = b.do("GET", "/v1/products/"+bought+"/reviews/mine", nil).json(t)
	if me["eligible"] != false || me["reason"] != "already-reviewed" || me["review"] == nil {
		t.Fatalf("mine after writing = %v", me)
	}
	if got := b.do("GET", "/v1/products/"+bought+"/reviews", nil).json(t)["rating"].(map[string]any)["count"].(float64); got != 3 {
		t.Fatalf("count after writing = %v", got)
	}

	// The product page and its cards carry the rating.
	prod := b.do("GET", "/v1/products/"+bought, nil)
	mustStatus(t, prod, 200)

	// Edit within the window keeps the photo (by URL) and changes the words.
	e := b.do("PUT", "/v1/products/"+bought+"/reviews/mine", map[string]any{"rating": 5, "title": "Lovely", "text": "Even better the second jar.",
		"photos": []any{map[string]any{"url": testkit.ReviewPhotoURL}}})
	mustStatus(t, e, 200)
	if e.json(t)["rating"].(float64) != 5 {
		t.Fatalf("edited = %s", e.body)
	}
	mustStatus(t, b.do("PUT", "/v1/products/"+bought+"/reviews/mine", map[string]any{"rating": 5, "photos": []any{map[string]any{"url": "https://evil.example/x.jpg"}}}), 422)
	mustStatus(t, b.do("PUT", "/v1/products/"+notBought+"/reviews/mine", map[string]any{"rating": 5}), 404)

	// Reports: others' reviews can be reported once; your own cannot.
	mustStatus(t, b.do("POST", "/v1/reviews/"+testkit.ReviewSeededID+"/reports", map[string]any{"reasonCode": "SPAM"}), 201)
	mustStatus(t, b.do("POST", "/v1/reviews/"+testkit.ReviewSeededID+"/reports", map[string]any{"reasonCode": "SPAM"}), 200)
	mustStatus(t, b.do("POST", "/v1/reviews/"+testkit.ReviewSeededID+"/reports", map[string]any{"reasonCode": "NOPE"}), 422)
	mustStatus(t, b.do("POST", "/v1/reviews/"+own["id"].(string)+"/reports", map[string]any{"reasonCode": "SPAM"}), 403)

	// After 30 days the review can no longer be edited, but can still be deleted.
	bff.Upstreams.ExpireReviewEdits()
	closed := b.do("PUT", "/v1/products/"+bought+"/reviews/mine", map[string]any{"rating": 1})
	mustStatus(t, closed, 409)
	if code := closed.json(t)["code"]; code != "REVIEW_EDIT_WINDOW_CLOSED" {
		t.Fatalf("closed code = %v", code)
	}
	mustStatus(t, b.do("DELETE", "/v1/products/"+bought+"/reviews/mine", nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/products/"+bought+"/reviews/mine", nil), 404)
	if got := b.do("GET", "/v1/products/"+bought+"/reviews", nil).json(t)["rating"].(map[string]any)["count"].(float64); got != 2 {
		t.Fatalf("count after deleting = %v", got)
	}

	for _, op := range []string{"listProductReviews", "getMyProductReview", "writeProductReview", "editMyProductReview", "deleteMyProductReview",
		"reportReview", "requestReviewPhotoUpload", "completeReviewPhotoUpload"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
