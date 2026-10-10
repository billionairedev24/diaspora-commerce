package testkit

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// Offer ids of the fake offers service.
const (
	// OfferKenteSheaID is Kente Naturals' buy box offer on ATSHEAV001.
	OfferKenteSheaID = "0a0a0a0a-0000-4000-8000-000000000001"
	// OfferAccraSheaID is Accra Market's other offer on ATSHEAV001 (ships from seller, Houston).
	OfferAccraSheaID = "0a0a0a0a-0000-4000-8000-000000000002"
	// OfferRetailJollofID is AfroTransact Retail's offer on ATJOLLOFV1.
	OfferRetailJollofID = "0a0a0a0a-0000-4000-8000-000000000003"
	// OfferPausedID is a paused offer (not buyable).
	OfferPausedID = "0a0a0a0a-0000-4000-8000-0000000000ff"
)

func publicOffer(id, atin, product, sellerID, name, kind, city, fulfilment string, price int64, qty int, badges ...string) map[string]any {
	status := "IN_STOCK"
	switch {
	case qty <= 0:
		status = "OUT_OF_STOCK"
	case qty <= 5:
		status = "LOW_STOCK"
	}
	return map[string]any{"offerId": id, "atin": atin, "productAtin": product, "condition": "NEW",
		"seller": map[string]any{"id": sellerID, "name": name, "kind": kind, "city": city}, "priceMinor": price, "shippingMinor": 0,
		"landedPriceMinor": price, "currency": "USD", "fulfilment": fulfilment, "availableQty": qty, "stockStatus": status,
		"handlingDays": 1, "deliveryDays": 3, "shipsFrom": city, "soldBy": name, "buyBoxRank": 1, "badges": badges}
}

func (u *Upstreams) fakeOffers() map[string][]map[string]any {
	accra := publicOffer(OfferAccraSheaID, "ATSHEAV001", "ATSHEA0001", "org_accra", "Accra Market", "SELLER", "Houston", "SELLER", 1300, 7, "SHIPS_FROM_SELLER")
	accra["buyBoxRank"] = 2
	return map[string][]map[string]any{
		"ATSHEAV001": {publicOffer(OfferKenteSheaID, "ATSHEAV001", "ATSHEA0001", "org_kente", "Kente Naturals", "SELLER", "Atlanta", "SELLER", 1250, 40,
			"BUY_BOX_WINNER", "LOWEST_PRICE"), accra},
		"ATJOLLOFV1": {publicOffer(OfferRetailJollofID, "ATJOLLOFV1", "ATJOLLOF01", "org_afrotransact_retail", "AfroTransact Retail", "PLATFORM",
			"AfroTransact", "AFROTRANSACT", 599, 3, "BUY_BOX_WINNER", "FULFILLED_BY_AFROTRANSACT", "SOLD_BY_AFROTRANSACT")},
	}
}

func buyBoxJSON(atin string, offers []map[string]any) map[string]any {
	box := map[string]any{"atin": atin, "currency": "USD", "buyBox": nil, "reasons": []any{}, "otherOffers": []any{}, "offerCount": len(offers),
		"minPriceMinor": nil}
	if len(offers) > 0 {
		box["buyBox"] = offers[0]
		others := []any{}
		for _, o := range offers[1:] {
			others = append(others, o)
		}
		box["otherOffers"] = others
		low := offers[0]["priceMinor"].(int64)
		for _, o := range offers {
			low = min(low, o["priceMinor"].(int64))
		}
		box["minPriceMinor"] = low
	}
	return box
}

func (u *Upstreams) offersRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/public/atins/{atin}/offers", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "max-age=15, public")
		writeJSON(w, 200, buyBoxJSON(r.PathValue("atin"), u.fakeOffers()[r.PathValue("atin")]))
	})
	mux.HandleFunc("GET /v1/public/buy-boxes", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("by") != "product" {
			problem(w, 400, "offers.rule-violation", "test fake serves product summaries only", nil)
			return
		}
		byProduct := map[string][]map[string]any{}
		for _, offers := range u.fakeOffers() {
			for _, o := range offers {
				p := o["productAtin"].(string)
				byProduct[p] = append(byProduct[p], o)
			}
		}
		items := []any{}
		for _, a := range strings.Split(r.URL.Query().Get("atins"), ",") {
			box := buyBoxJSON(a, byProduct[a])
			box["otherOffers"] = []any{}
			items = append(items, box)
		}
		w.Header().Set("Cache-Control", "max-age=15, public")
		writeJSON(w, 200, map[string]any{"items": items})
	})
	mux.HandleFunc("GET /v1/public/offers/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if id == OfferPausedID {
			writeJSON(w, 200, map[string]any{"offer": publicOffer(id, "ATSHEAV001", "ATSHEA0001", "org_lagos", "Lagos Pantry", "SELLER", "Dallas", "SELLER", 1100, 3),
				"buyable": false, "notEligible": []string{"NOT_ACTIVE"}})
			return
		}
		for _, offers := range u.fakeOffers() {
			for _, o := range offers {
				if o["offerId"] == id {
					writeJSON(w, 200, map[string]any{"offer": o, "buyable": true, "notEligible": []string{}})
					return
				}
			}
		}
		problem(w, 404, "offers.offer-not-found", "Offer "+id+" not found", nil)
	})
}

func (u *Upstreams) notificationsRoutes(mux *http.ServeMux) {
	type alert struct {
		ATIN, Email, Title, Status string
		CreatedAt                  time.Time
	}
	alerts := map[string]map[string]*alert{} // subject → atin → alert
	mux.HandleFunc("POST /v1/stock-alerts", func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		if sub == "" {
			problem(w, 401, "notifications.unauthenticated", "A shopper token is required", nil)
			return
		}
		var b struct {
			ATIN         string `json:"atin"`
			Email        string `json:"email"`
			ProductTitle string `json:"productTitle"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if alerts[sub] == nil {
			alerts[sub] = map[string]*alert{}
		}
		a, exists := alerts[sub][b.ATIN]
		status := 201
		if exists && a.Status == "active" {
			status = 200
		} else {
			a = &alert{ATIN: b.ATIN, Email: b.Email, Title: b.ProductTitle, Status: "active", CreatedAt: time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)}
			alerts[sub][b.ATIN] = a
		}
		writeJSON(w, status, map[string]any{"id": "11111111-1111-4111-8111-111111111111", "atin": a.ATIN, "email": a.Email, "productTitle": a.Title,
			"status": a.Status, "market": "us", "locale": "en", "createdAt": a.CreatedAt})
	})
	mux.HandleFunc("GET /v1/stock-alerts", func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		u.mu.Lock()
		defer u.mu.Unlock()
		items := []any{}
		for _, a := range alerts[sub] {
			items = append(items, map[string]any{"id": "11111111-1111-4111-8111-111111111111", "atin": a.ATIN, "productTitle": a.Title,
				"status": a.Status, "market": "us", "locale": "en", "createdAt": a.CreatedAt})
		}
		writeJSON(w, 200, map[string]any{"items": items})
	})
	mux.HandleFunc("DELETE /v1/stock-alerts/{atin}", func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		u.mu.Lock()
		defer u.mu.Unlock()
		if _, ok := alerts[sub][r.PathValue("atin")]; !ok {
			problem(w, 404, "notifications.alert-not-found", "No alert for this ATIN", nil)
			return
		}
		delete(alerts[sub], r.PathValue("atin"))
		w.WriteHeader(204)
	})
}
