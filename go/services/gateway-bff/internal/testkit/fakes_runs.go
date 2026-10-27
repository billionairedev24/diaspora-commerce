package testkit

import (
	"encoding/json"
	"net/http"
	"strings"
)

// Fixed ids of the fake delivery-runs data (S262, S263).
const (
	RunLocationID      = "4b2f9d3a-0000-4000-8000-00000000000a"
	RunLocationSlug    = "lubbock"
	RunAdvertorialID   = "5c3a0e4b-0000-4000-8000-0000000000a1"
	RunSubscriptionTok = "sub-token-1"
)

func runLocation() map[string]any {
	return map[string]any{"id": RunLocationID, "slug": RunLocationSlug, "name": "Lubbock", "market": "US", "state": "TX",
		"timeZone": "America/Chicago", "area": map[string]any{"kind": "ZIP_LIST", "zips": []string{"79401"}},
		"defaultFeeMinor": 799, "currency": "USD", "dropMode": "DOORSTEP", "pickupPoint": nil,
		"whatsappInviteUrl": "https://chat.example/join/lubbock", "socialLinks": map[string]string{"instagram": "https://ig.example/afrotransact"}}
}

func runSubscription(channels []string, granted bool) map[string]any {
	consents := []any{}
	for _, ch := range channels {
		c := map[string]any{"channel": ch, "granted": granted, "consentedAt": "2026-10-20T10:00:00Z", "source": "account", "withdrawnAt": nil}
		if !granted {
			c["withdrawnAt"] = "2026-10-21T10:00:00Z"
		}
		consents = append(consents, c)
	}
	return map[string]any{"id": "6d4b1f5c-0000-4000-8000-0000000000b1", "locationId": RunLocationID, "status": "ACTIVE", "consents": consents,
		"createdAt": "2026-10-20T10:00:00Z", "confirmedAt": "2026-10-20T10:00:00Z"}
}

// runAlertRoutes fakes delivery-runs' public location, subscription and advertorial routes and the signed-in shopper's
// /v1/me/run-subscriptions.
func (u *Upstreams) runAlertRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/public/locations", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []any{runLocation()})
	})
	mux.HandleFunc("GET /v1/public/locations/{slug}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("slug") != RunLocationSlug {
			problem(w, 404, "delivery-runs.not-found", "No such location", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"location": runLocation(), "locationUrl": "https://afrotransact.example/delivery-runs/lubbock",
			"shareLinks": map[string]string{"whatsapp": "https://wa.me/?text=lubbock"},
			"runs": []any{map[string]any{"runId": "7e5c2a6d-0000-4000-8000-0000000000c1", "locationId": RunLocationID, "locationSlug": RunLocationSlug,
				"town": "Lubbock", "status": "OPEN", "windowStart": "2026-10-25T15:00:00Z", "windowEnd": "2026-10-25T19:00:00Z",
				"cutoffAt": "2026-10-23T23:00:00Z", "feeMinor": 799, "currency": "USD", "dropMode": "DOORSTEP", "pickupPoint": nil,
				"acceptingOrders": true, "ordersLeft": 40, "full": false, "runUrl": "https://afrotransact.example/delivery-runs/lubbock?run=7e5c"}}})
	})
	mux.HandleFunc("POST /v1/public/subscriptions", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastWaitlist = b
		u.mu.Unlock()
		if b["locationId"] != nil {
			writeJSON(w, 202, map[string]any{"status": "CONFIRMATION_SENT"})
			return
		}
		writeJSON(w, 202, map[string]any{"status": "WAITLISTED"})
	})
	mux.HandleFunc("POST /v1/public/subscriptions/confirm", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["token"] != RunSubscriptionTok {
			problem(w, 422, "delivery-runs.invalid-token", "This link is not valid", nil)
			return
		}
		writeJSON(w, 200, runSubscription([]string{"EMAIL"}, true))
	})
	mux.HandleFunc("POST /v1/public/subscriptions/unsubscribe", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastRunUnsubscribe = b
		u.mu.Unlock()
		if b["token"] != RunSubscriptionTok {
			problem(w, 422, "delivery-runs.invalid-token", "This link is not valid", nil)
			return
		}
		writeJSON(w, 200, runSubscription([]string{"EMAIL"}, false))
	})
	mux.HandleFunc("GET /v1/me/run-subscriptions", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		writeJSON(w, 200, []any{runSubscription([]string{"EMAIL", "SMS"}, true)})
	})
	mux.HandleFunc("POST /v1/me/run-subscriptions", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastRunSubscribeMine = b
		u.mu.Unlock()
		var chs []string
		for _, c := range b["channels"].([]any) {
			chs = append(chs, c.(string))
		}
		writeJSON(w, 200, runSubscription(chs, true))
	})
	mux.HandleFunc("DELETE /v1/me/run-subscriptions/{locationId}", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		u.LastRunUnsubscribeMine = r.URL.Query()["channel"]
		u.mu.Unlock()
		chs := r.URL.Query()["channel"]
		if len(chs) == 0 {
			chs = []string{"EMAIL", "SMS"}
		}
		writeJSON(w, 200, runSubscription(chs, false))
	})
	mux.HandleFunc("POST /v1/public/locations/{slug}/visits", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastRunVisit = b
		u.mu.Unlock()
		if r.PathValue("slug") != RunLocationSlug {
			problem(w, 404, "delivery-runs.location-not-found", "Location not found", nil)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("GET /v1/public/advertorials", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		u.mu.Lock()
		u.LastAdvertorialQuery = q
		u.mu.Unlock()
		if q.Get("zip") != "79401" && !strings.EqualFold(q.Get("city"), "Lubbock") {
			writeJSON(w, 200, map[string]any{"items": []any{}})
			return
		}
		writeJSON(w, 200, map[string]any{"items": []any{map[string]any{"id": RunAdvertorialID, "source": "WINDOW",
			"sourceId": "7b1e2c3d-0000-4000-8000-000000000007", "variant": "A", "code": "LBK-7", "place": "Lubbock, TX",
			"headline": "Lubbock pre-order closes Thursday", "body": "Order African groceries before the cutoff.", "ctaLabel": "Order now",
			"ctaUrl":   "https://afrotransact.example/pre-orders/LBK-7?utm_source=advertorial&src=page&cr=adv-0123456789abcdef0123456789abcdef&po=LBK-7",
			"imageUrl": nil, "countdownTarget": "2026-10-22T04:59:00Z", "expiresAt": "2026-10-22T04:59:00Z",
			"deliveryDisplay": "Sunday, October 25", "feeDisplay": "$7.99",
			"featuredProducts": []any{map[string]any{"atin": "ATSHEA0001", "title": "Shea butter", "imageUrl": "https://cdn.example/shea.jpg"}, map[string]any{"atin": "ATGARI0001", "title": "Garri"}}}}})
	})
	mux.HandleFunc("POST /v1/public/advertorials/{id}/events", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if r.PathValue("id") != RunAdvertorialID {
			problem(w, 404, "delivery-runs.not-found", "No such advertorial", nil)
			return
		}
		u.mu.Lock()
		u.AdvertorialEvents = append(u.AdvertorialEvents, b["type"].(string))
		u.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	})
}
