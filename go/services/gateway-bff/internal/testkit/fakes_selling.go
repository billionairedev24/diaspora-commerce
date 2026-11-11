package testkit

import (
	"encoding/json"
	"net/http"
)

// Selling approvals fixtures: the fake sellerservice says dietary supplements need approval, bundles are not available and
// everything else sells. Applying with the wrong documents names them; a good application is filed once.
func (u *Upstreams) sellingRoutes(mux *http.ServeMux) {
	filed := []map[string]any{}
	slots := []map[string]any{
		{"kind": "PERMIT_LICENCE", "label": "Permit or licence (with its expiry date)", "optional": false, "expiryRequired": true, "dated": false, "namesAfroTransact": false},
		{"kind": "LIABILITY_INSURANCE", "label": "Liability insurance naming AfroTransact as additional insured (with its expiry date)", "optional": false, "expiryRequired": true, "dated": false, "namesAfroTransact": true},
	}
	missing := func(state, message string, canApply bool) map[string]any {
		return map[string]any{"scope": map[string]any{"type": "CATEGORY", "key": "dietary-supplements"}, "label": "Dietary supplements", "level": "APPLY", "state": state,
			"message": message, "canApply": canApply, "documents": []map[string]any{{"kind": "PERMIT_LICENCE", "optional": false}, {"kind": "LIABILITY_INSURANCE", "optional": false}}}
	}
	application := func(org string) map[string]any {
		return map[string]any{"id": "sap_fake0000000000000001", "reference": "SA-50001", "scopeType": "CATEGORY", "scopeKey": "dietary-supplements", "scopeLabel": "Dietary supplements",
			"kind": "NEW", "status": "PENDING_REVIEW", "statusWords": "With our team", "message": "We aim to answer within 2 business days.", "requestedItems": []string{},
			"disqualified": false, "documents": []map[string]any{}, "required": slots, "submittedAt": "2026-11-11T10:00:00Z", "updatedAt": "2026-11-11T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/orgs/{orgId}/selling-status", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		switch r.URL.Query().Get("categoryCode") {
		case "dietary-supplements":
			if len(filed) > 0 {
				writeJSON(w, 200, map[string]any{"level": "APPLY", "reason": "Your application for Dietary supplements is with our team. We aim to answer within 2 business days.",
					"missing": []map[string]any{missing("APPLIED", "Your application for Dietary supplements is with our team.", false)}, "pendingFacts": []string{}})
				return
			}
			writeJSON(w, 200, map[string]any{"level": "APPLY", "reason": "You need approval to sell in Dietary supplements. Apply once; after that every listing here goes live.",
				"missing": []map[string]any{missing("NOT_APPLIED", "You need approval to sell in Dietary supplements. Apply once; after that every listing here goes live.", true)}, "pendingFacts": []string{}})
		case "gift-baskets-bundles":
			writeJSON(w, 200, map[string]any{"level": "NOT_AVAILABLE", "reason": "Not available yet: blocked until bundles can be itemised for tax.",
				"missing": []map[string]any{{"scope": map[string]any{"type": "CATEGORY", "key": "gift-baskets-bundles"}, "label": "Gift baskets / bundles", "level": "NOT_AVAILABLE", "state": "NOT_AVAILABLE",
					"message": "Not available yet: blocked until bundles can be itemised for tax.", "canApply": false, "documents": []map[string]any{}}}, "pendingFacts": []string{}})
		case "jewelry-accessories":
			writeJSON(w, 200, map[string]any{"level": "SELL", "missing": []map[string]any{}, "pendingFacts": []string{"material"}})
		default:
			writeJSON(w, 200, map[string]any{"level": "SELL", "missing": []map[string]any{}, "pendingFacts": []string{}})
		}
	})
	mux.HandleFunc("GET /v1/orgs/{orgId}/selling-approvals", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"approvals": []map[string]any{{"scopeType": "CATEGORY", "scopeKey": "dietary-supplements", "label": "Dietary supplements", "level": "APPLY",
			"state": "NOT_APPLIED", "statusWords": "Not applied", "message": "You need approval to sell in Dietary supplements.", "canApply": true, "documents": slots, "onFile": []map[string]any{}, "listingsPaused": false}},
			"notices": []map[string]any{}, "capabilities": map[string]any{"foodService": map[string]any{"approved": false, "state": "NOT_APPLIED"}}})
	})
	mux.HandleFunc("GET /v1/orgs/{orgId}/selling-capabilities", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"foodService": map[string]any{"approved": false, "state": "NOT_APPLIED"}})
	})
	mux.HandleFunc("GET /v1/orgs/{orgId}/selling-applications", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		out := []map[string]any{}
		for range filed {
			out = append(out, application(r.PathValue("orgId")))
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("GET /v1/orgs/{orgId}/selling-applications/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "sap_fake0000000000000001" {
			problem(w, 404, "seller.selling-application-not-found", "Application not found", nil)
			return
		}
		writeJSON(w, 200, application(r.PathValue("orgId")))
	})
	mux.HandleFunc("POST /v1/orgs/{orgId}/selling-applications", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			ScopeKey  string           `json:"scopeKey"`
			Documents []map[string]any `json:"documents"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		if in.ScopeKey == "packaged-snacks" {
			problem(w, 422, "seller.selling-not-needed", "You can sell here without approval", nil)
			return
		}
		if len(in.Documents) < 2 {
			problem(w, 422, "seller.selling-documents-invalid", "Some documents are missing or need fixing", map[string]any{"fieldErrors": map[string]string{"documents.LIABILITY_INSURANCE": "Upload: Liability insurance"}})
			return
		}
		u.mu.Lock()
		if len(filed) == 0 {
			filed = append(filed, application(r.PathValue("orgId")))
		}
		u.mu.Unlock()
		writeJSON(w, 201, application(r.PathValue("orgId")))
	})
	mux.HandleFunc("POST /v1/orgs/{orgId}/selling-applications/{id}/resubmit", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "sap_fake0000000000000001" {
			problem(w, 404, "seller.selling-application-not-found", "Application not found", nil)
			return
		}
		problem(w, 409, "seller.selling-application-locked", "Application SA-50001 is PENDING_REVIEW and can't be changed", nil)
	})
}
