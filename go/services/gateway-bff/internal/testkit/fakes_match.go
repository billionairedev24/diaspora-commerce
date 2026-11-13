package testkit

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
)

// Barcodes and ATINs of the fake catalog's matching (S381, S382).
const (
	// MatchGTINLive is a UPC with a live page (the Shea butter page, two sellers).
	MatchGTINLive = "012345678905"
	// MatchGTINHeld is an EAN whose page exists; the seller has no tax decision for it yet, so an offer is TAX_HELD.
	MatchGTINHeld = "5901234123457"
	// MatchGTINBlocked is an EAN with a page that is not live (canOffer false).
	MatchGTINBlocked = "4006381333931"
	// MatchGTINWrongDigit has a wrong check digit (suggestion MatchGTINLive).
	MatchGTINWrongDigit = "012345678900"
	// MatchGTINStore is a store-use prefix (RESTRICTED).
	MatchGTINStore = "021234567893"
	// MatchATINHeld is the variant of MatchGTINHeld.
	MatchATINHeld = "ATHELDTAX1"
	// MatchATINOwn is a variant the organisation already sells (an offer on it answers offers.offer-exists).
	MatchATINOwn = "ATOWNOFFER"
)

var digitsOnly = regexp.MustCompile(`\D`)

func matchBody(result string, extra map[string]any) map[string]any {
	out := map[string]any{"result": result, "canOffer": false, "blockers": []any{}, "canCreate": result == "NO_MATCH", "createBlockers": []any{}}
	for k, v := range extra {
		out[k] = v
	}
	return out
}

func matchPage(atin, parent, title string, can bool, blockers []map[string]any) map[string]any {
	return matchBody("MATCH", map[string]any{"atin": atin, "parentAtin": parent, "productStatus": "LIVE", "title": title,
		"brand": "Kente Naturals", "image": map[string]any{"url": "https://img.example/shea.webp", "width": 1600, "height": 1600},
		"category": "Beauty > Skin care > Body butter", "variantOptions": map[string]string{"Size": "8 oz"},
		"variants": []map[string]any{{"atin": atin, "options": map[string]string{"Size": "8 oz"}}}, "canOffer": can, "blockers": blockers,
		"canCreate": false, "createBlockers": []map[string]any{{"code": "catalog.gtin-exists", "message": "A page for this barcode already exists."}}})
}

// matchRoutes is catalog's /v1/catalog/matches, change requests and image tasks, and offers' POST /v1/offers.
func (u *Upstreams) matchRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/catalog/matches", func(w http.ResponseWriter, r *http.Request) {
		var raw map[string]string
		_ = json.NewDecoder(r.Body).Decode(&raw)
		if raw["gtin"] == "" {
			problem(w, 422, "catalog.match-invalid", "Give a barcode", nil)
			return
		}
		digits := digitsOnly.ReplaceAllString(raw["gtin"], "")
		switch digits {
		case MatchGTINLive:
			writeJSON(w, 200, matchPage("ATSHEAV001", "ATSHEA0001", "Raw Shea Butter, 8 oz", true, []map[string]any{}))
		case MatchGTINHeld:
			writeJSON(w, 200, matchPage(MatchATINHeld, "ATHELDTAX0", "Black Soap, 8 oz", true, []map[string]any{}))
		case MatchGTINBlocked:
			writeJSON(w, 200, matchPage("ATNOTLIVE1", "ATNOTLIVE0", "Ankara Fabric, 6 yards", false,
				[]map[string]any{{"code": "offers.selling-approval-required", "message": "You need approval to sell this brand. Apply under Listings."}}))
		case MatchGTINWrongDigit:
			writeJSON(w, 200, matchBody("INVALID", map[string]any{"digits": digits, "suggestion": MatchGTINLive,
				"message": "That barcode does not look right. Did you mean " + MatchGTINLive + "?"}))
		case MatchGTINStore:
			writeJSON(w, 200, matchBody("RESTRICTED", map[string]any{"digits": digits, "message": "This number is for store use or coupons, not a product barcode."}))
		default:
			writeJSON(w, 200, matchBody("NO_MATCH", map[string]any{"digits": digits, "gtin14": strings.Repeat("0", 14-len(digits)) + digits}))
		}
	})

	// The search box: "shea" finds the Shea butter page (same name, states a size), "ghost" finds nothing, "limit" is rate limited,
	// "offline" has no search index. Nothing about any seller is in an answer.
	mux.HandleFunc("POST /v1/catalog/matches/candidates", func(w http.ResponseWriter, r *http.Request) {
		var in map[string]string
		_ = json.NewDecoder(r.Body).Decode(&in)
		q := strings.ToLower(in["query"])
		u.mu.Lock()
		u.LastCandidateQuery = in
		u.mu.Unlock()
		cat := map[string]any{"id": "0c0c0c0c-0000-4000-8000-000000000001", "path": "Beauty > Skin care > Body butter"}
		switch {
		case strings.Contains(q, "limit"):
			problem(w, 422, "catalog.match-rate-limited", "You searched a lot in the last hour. Try again later.", nil)
		case strings.Contains(q, "offline"):
			writeJSON(w, 200, map[string]any{"candidates": []any{}, "tooShort": false, "searchUnavailable": true})
		case strings.Contains(q, "shea"):
			writeJSON(w, 200, map[string]any{"candidates": []map[string]any{{"parentAtin": "ATSHEA0001", "atin": "ATSHEAV001", "title": "Raw Shea Butter, 8 oz",
				"brand": "Kente Naturals", "image": map[string]any{"url": "https://img.example/shea.webp", "width": 1600, "height": 1600},
				"category": "Beauty > Skin care > Body butter", "sizeLabel": "8 oz", "variants": []map[string]any{{"atin": "ATSHEAV001", "options": map[string]string{"Size": "8 oz"}}},
				"kind": "SAME_NAME", "likely": true, "canOffer": true, "blockers": []any{}}},
				"suggestedCategory": cat, "tooShort": false, "searchUnavailable": false})
		default:
			writeJSON(w, 200, map[string]any{"candidates": []any{}, "tooShort": len(q) < 3, "searchUnavailable": false})
		}
	})
	mux.HandleFunc("POST /v1/catalog/matches/decisions", func(w http.ResponseWriter, r *http.Request) {
		var in map[string]any
		_ = json.NewDecoder(r.Body).Decode(&in)
		switch in["choice"] {
		case "NONE_OF_THESE":
			writeJSON(w, 200, map[string]any{"decisionId": "0d0d0d0d-0000-4000-8000-000000000001", "choice": "NONE_OF_THESE", "canOffer": false, "blockers": []any{}, "possibleDuplicateQueued": true})
		case "SELL_EXISTING":
			if in["sizeConfirmed"] != true {
				problem(w, 422, "catalog.match-size-unconfirmed", "Confirm the size first.", nil)
				return
			}
			if in["atin"] != "ATSHEAV001" {
				problem(w, 422, "catalog.match-choice-not-shown", "That page was not in the list.", nil)
				return
			}
			writeJSON(w, 200, map[string]any{"decisionId": "0d0d0d0d-0000-4000-8000-000000000002", "choice": "SELL_EXISTING", "atin": "ATSHEAV001", "canOffer": true, "blockers": []any{}, "possibleDuplicateQueued": false})
		default:
			problem(w, 422, "catalog.match-choice-missing", "Choose an answer.", nil)
		}
	})

	offers := map[string]bool{MatchATINOwn: true}
	mux.HandleFunc("POST /v1/offers", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		atin, _ := b["atin"].(string)
		u.mu.Lock()
		defer u.mu.Unlock()
		u.LastOfferBody = b
		if offers[atin] {
			problem(w, 409, "offers.offer-exists", "You already sell this item in that condition. Change your offer under Listings.", nil)
			return
		}
		if atin == "ATNOTLIVE1" {
			problem(w, 422, "offers.selling-approval-required", "You need approval to sell this brand. Apply under Listings.", nil)
			return
		}
		offers[atin] = true
		status := "ACTIVE"
		held := any(nil)
		if atin == MatchATINHeld {
			status, held = "TAX_HELD", "TAX_DECISION_MISSING"
		}
		sku, _ := b["sellerSku"].(string)
		writeJSON(w, 201, map[string]any{"id": "0b0b0b0b-0000-4000-8000-000000000001", "atin": atin, "productAtin": "ATSHEA0001", "productId": "p-1", "sellerSku": sku,
			"condition": b["condition"], "priceMinor": b["priceMinor"], "currency": "USD", "fulfilment": b["fulfilment"], "quantity": b["quantity"],
			"availableQty": 0, "status": status, "heldReason": held, "version": 1})
	})

	requests := []map[string]any{}
	mux.HandleFunc("POST /v1/products/{atin}/change-requests", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		atin := r.PathValue("atin")
		typ, _ := b["type"].(string)
		u.mu.Lock()
		defer u.mu.Unlock()
		if atin == "ATNOOFFER1" && typ != "WRONG_MATCH" && typ != "DUPLICATE_PAGE" {
			problem(w, 403, "catalog.not-your-offer", "You can suggest a change once you sell this item.", nil)
			return
		}
		for _, q := range requests {
			if q["atin"] == atin && q["type"] == typ && q["status"] == "PENDING" {
				problem(w, 409, "catalog.change-request-exists", "You already sent this. We will tell you when we have looked at it.", nil)
				return
			}
		}
		if typ == "WRONG_MATCH" && atin == "ATWRONGOPN" {
			problem(w, 409, "catalog.wrong-match-open", "We are checking this barcode. We will tell you within 2 business days.", nil)
			return
		}
		req := map[string]any{"id": "c0c0c0c0-0000-4000-8000-00000000000" + string(rune('1'+len(requests))), "atin": atin, "title": "Raw Shea Butter, 8 oz", "type": typ,
			"field": b["field"], "payload": b["payload"], "note": b["note"], "status": "PENDING", "requestedByOrg": "org_kente", "requestedAt": "2026-11-12T09:00:00Z",
			"slaDueAt": "2026-11-16T09:00:00Z", "version": 1}
		requests = append(requests, req)
		writeJSON(w, 201, req)
	})
	mux.HandleFunc("GET /v1/change-requests/mine", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		items := []map[string]any{}
		for _, q := range requests {
			if s := r.URL.Query().Get("status"); s == "" || q["status"] == s {
				items = append(items, q)
			}
		}
		writeJSON(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 25})
	})
	mux.HandleFunc("POST /v1/change-requests/{id}/withdraw", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		for _, q := range requests {
			if q["id"] != r.PathValue("id") {
				continue
			}
			if q["status"] != "PENDING" {
				problem(w, 409, "catalog.change-request-decided", "This request was already decided.", nil)
				return
			}
			q["status"] = "WITHDRAWN"
			writeJSON(w, 200, q)
			return
		}
		problem(w, 404, "catalog.change-request-not-found", "No such request", nil)
	})
	mux.HandleFunc("GET /v1/products/image-tasks", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"items": []map[string]any{
			{"productId": "p-1", "atin": "ATADE00001", "title": "Ofada rice 5 lb", "state": "FAIL", "failingSince": "2026-11-01T00:00:00Z", "hideAfter": "2026-12-31T00:00:00Z",
				"daysLeft": 48, "hidden": false, "reasons": []map[string]any{{"code": "NOT_WHITE", "level": "REJECT", "message": "The main photo needs a plain white background."}}},
			{"productId": "p-2", "atin": "ATSHEA0001", "title": "Raw Shea Butter, 8 oz", "state": "IMPROVE", "hidden": false,
				"reasons": []map[string]any{{"code": "FILL_OK_NEAR", "level": "WARN", "message": "The product could fill more of the photo."}}},
		}})
	})
}
