package testkit

import (
	"fmt"
	"net/http"
	"slices"
	"strconv"
)

// shopperClaimRoutes mounts trust-ops' customer claim routes: open, mine and one claim, each scoped to the token's
// subject like the real service (another customer's claim answers 404).
func (u *Upstreams) shopperClaimRoutes(mux *http.ServeMux, st *shopperState, authed func(http.HandlerFunc) http.HandlerFunc) {
	mux.HandleFunc("POST /v1/claims", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		b := readJSON(r)
		if b["orderId"] != ShopperOrderID || b["orderNumber"] != ShopperOrderNumber {
			problem(w, 404, "trust.order-not-found", "No such order", nil)
			return
		}
		if _, set := b["customerId"]; set {
			problem(w, 403, "trust.forbidden", "customerId is staff-only", nil)
			return
		}
		amount, _ := b["amountMinor"].(float64)
		if amount < 1 || b["currency"] == "" || b["description"] == "" {
			problem(w, 422, "trust.invalid-claim", "Invalid claim", nil)
			return
		}
		var evidence []map[string]any
		if ids, _ := b["evidenceAssetIds"].([]any); len(ids) > 0 {
			for _, id := range ids {
				evidence = append(evidence, map[string]any{"assetId": id, "party": "CUSTOMER", "addedBy": sub, "addedAt": "2026-10-20T10:00:00Z"})
			}
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		n := len(st.claims) + 1
		claim := map[string]any{"id": fmt.Sprintf("0e0e0e0e-0000-4000-8000-%012d", n), "reference": fmt.Sprintf("CLM-%04d", n), "type": b["type"],
			"status": "AWAITING_SELLER", "orderId": b["orderId"], "orderNumber": b["orderNumber"], "shipmentId": b["shipmentId"],
			"sellerOrgId": firstNonNil(b["sellerOrgId"], ShopperSellerOrgID), "customerId": sub, "amountMinor": amount, "currency": b["currency"],
			"description": b["description"], "evidence": evidence, "sla": map[string]any{"respondBy": "2026-10-23T10:00:00Z"}, "escalated": false,
			"openedAt": "2026-10-20T10:00:00Z", "updatedAt": "2026-10-20T10:00:00Z", "version": 0}
		st.claims = append(st.claims, claim)
		writeJSON(w, 201, claim)
	})
	mux.HandleFunc("GET /v1/claims/mine", authed(func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		size, err := strconv.Atoi(r.URL.Query().Get("size"))
		if err != nil || size < 1 {
			size = 20
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		var mine []map[string]any
		for _, c := range st.claims {
			if c["customerId"] == sub {
				mine = append(mine, c)
			}
		}
		slices.Reverse(mine)
		items := []map[string]any{}
		if from := page * size; from < len(mine) {
			items = mine[from:min(from+size, len(mine))]
		}
		writeJSON(w, 200, map[string]any{"items": items, "total": len(mine), "page": page, "size": size})
	}))
	mux.HandleFunc("GET /v1/claims/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		for _, c := range st.claims {
			if c["id"] == r.PathValue("id") && c["customerId"] == sub {
				writeJSON(w, 200, c)
				return
			}
		}
		problem(w, 404, "trust.claim-not-found", "No such claim", nil)
	}))
}

func firstNonNil(vals ...any) any {
	for _, v := range vals {
		if s, isString := v.(string); v != nil && (!isString || s != "") {
			return v
		}
	}
	return nil
}

// OpenedClaims are the claims the fake trust-ops holds (tests assert what the BFF sent).
func (u *Upstreams) OpenedClaims() []map[string]any {
	v, ok := shopperStates.Load(u)
	if !ok {
		return nil
	}
	st, _ := v.(*shopperState)
	st.mu.Lock()
	defer st.mu.Unlock()
	return slices.Clone(st.claims)
}

// DecideClaim plays staff deciding a claim in the claims tooling: outcome REFUND or DENY, with the staff note.
func (u *Upstreams) DecideClaim(id, outcome, note string, refundMinor int64) {
	v, ok := shopperStates.Load(u)
	if !ok {
		return
	}
	st, _ := v.(*shopperState)
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, c := range st.claims {
		if c["id"] != id {
			continue
		}
		c["outcome"], c["decisionNote"], c["decidedBy"], c["decidedAt"] = outcome, note, "staff-jane", "2026-10-22T09:00:00Z"
		c["updatedAt"] = "2026-10-22T09:00:00Z"
		if outcome == "REFUND" {
			c["status"], c["refundMinor"], c["refundId"] = "RESOLVED_REFUND", refundMinor, "rf_1"
		} else {
			c["status"] = "RESOLVED_DENIED"
		}
	}
}
