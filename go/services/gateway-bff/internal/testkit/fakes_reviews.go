package testkit

import (
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Product review fixtures (S324): the fake shopper has received ReviewBoughtATIN and not ReviewNotBoughtATIN. The
// shared product ATSHEA0001 already has two published reviews by other customers.
const (
	ReviewBoughtATIN    = "ATSHEA0001"
	ReviewNotBoughtATIN = "ATGARI0001"
	// ReviewSeededID is the published 5-star review of Amara O. on ReviewBoughtATIN.
	ReviewSeededID = "7a1b2c3d-0000-4000-8000-0000000000a1"
	// ReviewPhotoURL is the public URL media-service gives the fake photo upload.
	ReviewPhotoURL = "https://img.example/asset-1.webp"
)

type fakeReview struct {
	id, atin, customer, reviewer, title, text, status string
	rating                                            int
	photos                                            []string
	created                                           time.Time
	reply                                             map[string]any
	reportedBy                                        map[string]bool
	reason, note                                      string
}

type reviewState struct {
	mu      sync.Mutex
	reviews []*fakeReview
	seq     int
	// now is the fake clock (reviews are created "now"; ExpireReviewEdits moves them 40 days back).
	now func() time.Time
}

var reviewStates sync.Map

func (u *Upstreams) reviews() *reviewState {
	if v, ok := reviewStates.Load(u); ok {
		return v.(*reviewState)
	}
	base := time.Date(2026, 10, 1, 10, 0, 0, 0, time.UTC)
	st := &reviewState{now: time.Now, reviews: []*fakeReview{
		{id: ReviewSeededID, atin: ReviewBoughtATIN, customer: "cust-amara", reviewer: "Amara O.", rating: 5, title: "Smooth and pure",
			text: "Absorbs fast, no smell. Third jar.", status: "PUBLISHED", created: base, photos: []string{"https://img.example/review-1.jpg"},
			reportedBy: map[string]bool{}},
		{id: "7a1b2c3d-0000-4000-8000-0000000000a2", atin: ReviewBoughtATIN, customer: "cust-kofi", reviewer: "Kofi B.", rating: 3,
			text: "Good, but the lid cracked in the post.", status: "PUBLISHED", created: base.Add(24 * time.Hour), reportedBy: map[string]bool{}},
		{id: "7a1b2c3d-0000-4000-8000-0000000000a3", atin: "ATJOLLOF01", customer: "cust-zainab", reviewer: "Zainab K.", rating: 4,
			title: "Proper party jollof", status: "PUBLISHED", created: base.Add(48 * time.Hour), reportedBy: map[string]bool{}},
	}}
	actual, _ := reviewStates.LoadOrStore(u, st)
	return actual.(*reviewState)
}

// ExpireReviewEdits makes every review older than the 30-day edit window (the shopper's can no longer be edited).
func (u *Upstreams) ExpireReviewEdits() {
	st := u.reviews()
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, r := range st.reviews {
		r.created = r.created.Add(-40 * 24 * time.Hour)
	}
}

func (st *reviewState) live(atin, customer string) *fakeReview {
	for _, r := range st.reviews {
		if r.atin == atin && r.customer == customer && r.status != "DELETED" {
			return r
		}
	}
	return nil
}

func (st *reviewState) rating(atin string) map[string]any {
	counts := make([]int, 5)
	for _, r := range st.reviews {
		if r.atin == atin && r.status == "PUBLISHED" {
			counts[r.rating-1]++
		}
	}
	n, sum := 0, 0
	for i, c := range counts {
		n += c
		sum += c * (i + 1)
	}
	avg := 0.0
	if n > 0 {
		avg = float64(sum*100/n) / 100
	}
	return map[string]any{"average": avg, "count": n, "breakdown": counts}
}

func photoList(urls []string) []map[string]any {
	out := make([]map[string]any, 0, len(urls))
	for _, u := range urls {
		out = append(out, map[string]any{"url": u})
	}
	return out
}

func nullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (r *fakeReview) replyJSON() any {
	if r.reply == nil {
		return nil
	}
	return r.reply
}

func (r *fakeReview) public() map[string]any {
	return map[string]any{"id": r.id, "reviewerName": r.reviewer, "rating": r.rating, "title": nullable(r.title), "text": nullable(r.text),
		"photos": photoList(r.photos), "verifiedPurchase": true, "createdAt": r.created.Format(time.RFC3339), "reply": r.replyJSON()}
}

func (r *fakeReview) own(now time.Time) map[string]any {
	until := r.created.Add(30 * 24 * time.Hour)
	out := map[string]any{"id": r.id, "atin": r.atin, "productTitle": "Raw Shea Butter", "reviewerName": r.reviewer, "rating": r.rating,
		"title": nullable(r.title), "text": nullable(r.text), "photos": photoList(r.photos), "status": r.status,
		"createdAt": r.created.Format(time.RFC3339), "updatedAt": r.created.Format(time.RFC3339), "editableUntil": until.Format(time.RFC3339),
		"editable": now.Before(until), "reply": r.replyJSON()}
	if r.reason != "" {
		out["moderation"] = map[string]any{"by": "staff-1", "at": r.created.Format(time.RFC3339), "reasonCode": r.reason, "note": nullable(r.note)}
	}
	return out
}

func (r *fakeReview) seller() map[string]any {
	return map[string]any{"id": r.id, "atin": r.atin, "productTitle": "Raw Shea Butter", "reviewerName": r.reviewer, "rating": r.rating,
		"title": nullable(r.title), "text": nullable(r.text), "photos": photoList(r.photos), "verifiedPurchase": true,
		"createdAt": r.created.Format(time.RFC3339), "reportCount": len(r.reportedBy), "reply": r.replyJSON()}
}

// flagged mimics catalog's automatic checks: a link, an e-mail address or a phone number waits for moderation.
func flagged(title, text string) bool {
	all := strings.ToLower(title + " " + text)
	return strings.Contains(all, "http") || strings.Contains(all, "www.") || strings.Contains(all, "@")
}

func displayName(full string) string {
	parts := strings.Fields(full)
	switch len(parts) {
	case 0:
		return "Verified customer"
	case 1:
		return parts[0]
	}
	return parts[0] + " " + strings.ToUpper(parts[len(parts)-1][:1]) + "."
}

func draftOf(body map[string]any) (rating int, title, text string, photos []string, errs map[string]any) {
	errs = map[string]any{}
	if f, ok := body["rating"].(float64); ok {
		rating = int(f)
	}
	if rating < 1 || rating > 5 {
		errs["rating"] = "Choose 1 to 5 stars"
	}
	title, _ = body["title"].(string)
	text, _ = body["text"].(string)
	if ps, ok := body["photos"].([]any); ok {
		for _, p := range ps {
			if m, ok := p.(map[string]any); ok {
				if u, _ := m["url"].(string); u != "" {
					photos = append(photos, u)
				}
			}
		}
	}
	if len(photos) > 5 {
		errs["photos"] = "Up to 5 photos"
	}
	return rating, title, text, photos, errs
}

func (u *Upstreams) reviewRoutes(mux *http.ServeMux) {
	st := u.reviews()
	nowFn := func() time.Time { return st.now() }
	mux.HandleFunc("GET /v1/public/products/{atin}/reviews", func(w http.ResponseWriter, r *http.Request) {
		atin := r.PathValue("atin")
		u.mu.Lock()
		_, ok := u.product(atin)
		u.mu.Unlock()
		if !ok {
			problem(w, 404, "catalog.product-not-found", "No live product", nil)
			return
		}
		q := r.URL.Query()
		stars, _ := strconv.Atoi(q.Get("stars"))
		page, _ := strconv.Atoi(q.Get("page"))
		size, err := strconv.Atoi(q.Get("size"))
		if err != nil || size <= 0 {
			size = 10
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		var items []*fakeReview
		for _, rv := range st.reviews {
			if rv.atin == atin && rv.status == "PUBLISHED" && (stars == 0 || rv.rating == stars) {
				items = append(items, rv)
			}
		}
		slices.SortStableFunc(items, func(a, b *fakeReview) int {
			switch q.Get("sort") {
			case "HIGHEST":
				if a.rating != b.rating {
					return b.rating - a.rating
				}
			case "LOWEST":
				if a.rating != b.rating {
					return a.rating - b.rating
				}
			}
			return b.created.Compare(a.created)
		})
		total := len(items)
		from := min(page*size, total)
		items = items[from:min(from+size, total)]
		out := make([]map[string]any, 0, len(items))
		for _, rv := range items {
			out = append(out, rv.public())
		}
		w.Header().Set("Cache-Control", "max-age=30, public")
		writeJSON(w, 200, map[string]any{"rating": st.rating(atin), "items": out, "total": total, "page": page, "size": size})
	})
	mux.HandleFunc("GET /v1/products/{atin}/reviews/mine", customerOnly("catalog.customer-only", func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		atin, sub := r.PathValue("atin"), customerSubject(r)
		if rv := st.live(atin, sub); rv != nil {
			writeJSON(w, 200, map[string]any{"eligible": false, "reason": "already-reviewed", "review": rv.own(nowFn())})
			return
		}
		if atin != ReviewBoughtATIN {
			writeJSON(w, 200, map[string]any{"eligible": false, "reason": "not-purchased", "review": nil})
			return
		}
		writeJSON(w, 200, map[string]any{"eligible": true, "reason": nil, "review": nil})
	}))
	mux.HandleFunc("POST /v1/products/{atin}/reviews", customerOnly("catalog.customer-only", func(w http.ResponseWriter, r *http.Request) {
		atin, sub := r.PathValue("atin"), customerSubject(r)
		body := readJSON(r)
		rating, title, text, photos, errs := draftOf(body)
		st.mu.Lock()
		defer st.mu.Unlock()
		switch {
		case atin != ReviewBoughtATIN && atin != "ATJOLLOF01":
			problem(w, 403, "catalog.review-not-verified-purchase", "Only customers who received this item can review it", nil)
			return
		case st.live(atin, sub) != nil:
			problem(w, 409, "catalog.review-already-exists", "You have already reviewed this item", nil)
			return
		case len(errs) > 0:
			problem(w, 422, "catalog.rule-violation", "Check your review", map[string]any{"fieldErrors": errs})
			return
		}
		st.seq++
		rv := &fakeReview{id: "7a1b2c3d-0000-4000-8000-00000000" + strconv.Itoa(1000+st.seq), atin: atin, customer: sub, rating: rating,
			title: title, text: text, photos: photos, created: nowFn(), status: "PUBLISHED", reportedBy: map[string]bool{}}
		rv.reviewer = displayName(func() string { s, _ := body["reviewerName"].(string); return s }())
		if flagged(title, text) {
			rv.status = "PENDING"
		}
		st.reviews = append(st.reviews, rv)
		writeJSON(w, 201, rv.own(nowFn()))
	}))
	mux.HandleFunc("PUT /v1/products/{atin}/reviews/mine", customerOnly("catalog.customer-only", func(w http.ResponseWriter, r *http.Request) {
		rating, title, text, photos, errs := draftOf(readJSON(r))
		st.mu.Lock()
		defer st.mu.Unlock()
		rv := st.live(r.PathValue("atin"), customerSubject(r))
		switch {
		case rv == nil:
			problem(w, 404, "catalog.review-not-found", "You have no review", nil)
			return
		case !nowFn().Before(rv.created.Add(30 * 24 * time.Hour)):
			problem(w, 409, "catalog.review-edit-window-closed", "Reviews can be edited for 30 days after they were written", nil)
			return
		case len(errs) > 0:
			problem(w, 422, "catalog.rule-violation", "Check your review", map[string]any{"fieldErrors": errs})
			return
		}
		rv.rating, rv.title, rv.text, rv.photos = rating, title, text, photos
		rv.status, rv.reason, rv.note = "PUBLISHED", "", ""
		if flagged(title, text) {
			rv.status = "PENDING"
		}
		writeJSON(w, 200, rv.own(nowFn()))
	}))
	mux.HandleFunc("DELETE /v1/products/{atin}/reviews/mine", customerOnly("catalog.customer-only", func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		rv := st.live(r.PathValue("atin"), customerSubject(r))
		if rv == nil {
			problem(w, 404, "catalog.review-not-found", "You have no review", nil)
			return
		}
		rv.status = "DELETED"
		w.WriteHeader(204)
	}))
	mux.HandleFunc("POST /v1/product-reviews/{id}/reports", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		var rv *fakeReview
		for _, c := range st.reviews {
			if c.id == r.PathValue("id") && c.status == "PUBLISHED" {
				rv = c
			}
		}
		a := actorOf(r)
		switch {
		case rv == nil:
			problem(w, 404, "catalog.review-not-found", "Review not found", nil)
			return
		case a.Type == "CUSTOMER" && rv.customer == sub:
			problem(w, 403, "catalog.review-own-report", "You cannot report your own review", nil)
			return
		case a.Type != "CUSTOMER" && a.Type != "SELLER_MEMBER":
			problem(w, 403, "catalog.review-report-forbidden", "Only shoppers and the product's seller can report a review", nil)
			return
		}
		created := !rv.reportedBy[sub]
		rv.reportedBy[sub] = true
		status := 200
		if created {
			status = 201
		}
		writeJSON(w, status, map[string]any{"created": created, "reports": len(rv.reportedBy)})
	})
	mux.HandleFunc("GET /v1/seller/reviews", func(w http.ResponseWriter, r *http.Request) {
		a := actorOf(r)
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		if a.Type != "SELLER_MEMBER" || a.OrgID == "" {
			problem(w, 403, "auth.wrong-org", "Only members of a seller organisation", nil)
			return
		}
		q := r.URL.Query()
		unanswered := q.Get("unanswered") == "true"
		page, _ := strconv.Atoi(q.Get("page"))
		size, err := strconv.Atoi(q.Get("size"))
		if err != nil || size <= 0 {
			size = 20
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		var items []*fakeReview
		for _, rv := range st.reviews {
			// the signed-in seller owns the Kente Naturals product in the fixtures
			if rv.atin == ReviewBoughtATIN && rv.status == "PUBLISHED" && (!unanswered || rv.reply == nil) {
				items = append(items, rv)
			}
		}
		slices.SortStableFunc(items, func(a, b *fakeReview) int { return b.created.Compare(a.created) })
		total := len(items)
		from := min(page*size, total)
		items = items[from:min(from+size, total)]
		out := make([]map[string]any, 0, len(items))
		for _, rv := range items {
			out = append(out, rv.seller())
		}
		writeJSON(w, 200, map[string]any{"items": out, "total": total, "page": page, "size": size})
	})
	mux.HandleFunc("POST /v1/product-reviews/{id}/reply", func(w http.ResponseWriter, r *http.Request) {
		a := actorOf(r)
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		var rv *fakeReview
		for _, c := range st.reviews {
			if c.id == r.PathValue("id") {
				rv = c
			}
		}
		switch {
		case rv == nil || a.Type != "SELLER_MEMBER" || a.OrgID == "" || rv.atin != ReviewBoughtATIN:
			problem(w, 404, "catalog.review-not-found", "Review not found", nil)
			return
		case rv.status != "PUBLISHED":
			problem(w, 409, "catalog.review-not-published", "You can reply once the review is published", nil)
			return
		case rv.reply != nil:
			problem(w, 409, "catalog.review-already-replied", "You have already replied to this review", nil)
			return
		}
		text, _ := readJSON(r)["text"].(string)
		rv.reply = map[string]any{"text": strings.TrimSpace(text), "at": nowFn().Format(time.RFC3339)}
		writeJSON(w, 200, rv.seller())
	})
}

// withRating adds the product's rating over its published reviews to catalog's public product (S324).
func (u *Upstreams) withRating(m map[string]any) map[string]any {
	st := u.reviews()
	st.mu.Lock()
	defer st.mu.Unlock()
	m["rating"] = st.rating(m["atin"].(string))
	return m
}
