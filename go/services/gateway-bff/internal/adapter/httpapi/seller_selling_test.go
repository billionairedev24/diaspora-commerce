package httpapi_test

import (
	"encoding/json"
	"testing"
)

// exerciseSellerSelling drives the selling approvals routes through the real handlers; the caller validates every
// answer against gateway-bff-seller.yaml.
func exerciseSellerSelling(t *testing.T, b *browser) {
	t.Helper()
	// The three states of the product form's chip.
	open := b.do("GET", "/v1/seller/selling-status?categoryCode=packaged-snacks&brand=Tasty", nil)
	if open.status != 200 || open.json(t)["level"] != "SELL" {
		t.Fatalf("open = %d %s", open.status, open.body)
	}
	apply := b.do("GET", "/v1/seller/selling-status?categoryCode=dietary-supplements", nil)
	missing := apply.json(t)["missing"].([]any)
	if apply.status != 200 || apply.json(t)["level"] != "APPLY" || len(missing) != 1 || missing[0].(map[string]any)["canApply"] != true {
		t.Fatalf("apply = %d %s", apply.status, apply.body)
	}
	blocked := b.do("GET", "/v1/seller/selling-status?categoryCode=gift-baskets-bundles", nil)
	if blocked.status != 200 || blocked.json(t)["level"] != "NOT_AVAILABLE" || blocked.json(t)["reason"] == nil {
		t.Fatalf("blocked = %d %s", blocked.status, blocked.body)
	}
	jewelry := b.do("GET", "/v1/seller/selling-status?categoryCode=jewelry-accessories", nil)
	if jewelry.status != 200 || jewelry.json(t)["pendingFacts"].([]any)[0] != "material" {
		t.Fatalf("jewelry = %d %s", jewelry.status, jewelry.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/selling-approvals", nil), 200)
	if caps := b.do("GET", "/v1/seller/selling-capabilities", nil); caps.status != 200 || caps.json(t)["foodService"].(map[string]any)["approved"] != false {
		t.Fatalf("capabilities = %d %s", caps.status, caps.body)
	}

	// Applying: the shape is checked here, the documents by seller-service.
	docs := []any{
		map[string]any{"kind": "PERMIT_LICENCE", "assetId": "asset-permit", "expiresOn": "2027-06-30"},
		map[string]any{"kind": "LIABILITY_INSURANCE", "assetId": "asset-policy", "expiresOn": "2027-06-30", "namesAfroTransact": true},
	}
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications", map[string]any{}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "PLANET", "scopeKey": "x"}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "CATEGORY", "scopeKey": "dietary-supplements",
		"documents": []any{map[string]any{"kind": "PASSPORT", "assetId": "asset-x"}}}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "CATEGORY", "scopeKey": "dietary-supplements",
		"documents": []any{map[string]any{"kind": "PERMIT_LICENCE", "assetId": "asset-x", "expiresOn": "30/06/2027"}}}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "CATEGORY", "scopeKey": "packaged-snacks", "documents": docs}), 422)
	r := b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "CATEGORY", "scopeKey": "dietary-supplements",
		"documents": []any{map[string]any{"kind": "PERMIT_LICENCE", "assetId": "asset-permit", "expiresOn": "2027-06-30"}}})
	if r.status != 422 || r.json(t)["errors"] == nil {
		t.Fatalf("incomplete application = %d %s", r.status, r.body)
	}
	filed := b.do("POST", "/v1/seller/selling-applications", map[string]any{"scopeType": "CATEGORY", "scopeKey": "dietary-supplements", "documents": docs})
	if filed.status != 201 || filed.json(t)["statusWords"] != "With our team" {
		t.Fatalf("filed = %d %s", filed.status, filed.body)
	}
	after := b.do("GET", "/v1/seller/selling-status?categoryCode=dietary-supplements", nil).json(t)["missing"].([]any)[0].(map[string]any)
	if after["state"] != "APPLIED" || after["canApply"] != false {
		t.Fatalf("after applying = %v", after)
	}
	var listed []any
	list := b.do("GET", "/v1/seller/selling-applications", nil)
	if err := json.Unmarshal(list.body, &listed); err != nil || list.status != 200 || len(listed) != 1 {
		t.Fatalf("applications = %d %s", list.status, list.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/selling-applications/sap_fake0000000000000001", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/selling-applications/sap_unknown", nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/selling-applications/bad%20id", nil), 404)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications/sap_fake0000000000000001/resubmit", map[string]any{"documents": docs}), 409)
	mustStatus(t, b.do("POST", "/v1/seller/selling-applications/sap_unknown/resubmit", map[string]any{}), 404)
}
