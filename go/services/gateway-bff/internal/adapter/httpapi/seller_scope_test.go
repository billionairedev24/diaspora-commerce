package httpapi_test

import (
	"encoding/json"
	"testing"
)

// exerciseSellerScope drives the department scope routes through the real handlers; the caller validates every answer
// against gateway-bff-seller.yaml.
func exerciseSellerScope(t *testing.T, b *browser) {
	t.Helper()
	scope := b.do("GET", "/v1/seller/catalog-scope", nil)
	if scope.status != 200 || len(scope.json(t)["departments"].([]any)) != 2 || scope.json(t)["hasSizing"] != true {
		t.Fatalf("scope = %d %s", scope.status, scope.body)
	}
	var departments []any
	r := b.do("GET", "/v1/seller/departments", nil)
	if err := json.Unmarshal(r.body, &departments); r.status != 200 || err != nil || len(departments) != 3 {
		t.Fatalf("departments = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/catalog/categories", nil), 200)

	// Asking for another department: a plain answer that names what is still needed.
	asked := b.do("POST", "/v1/seller/department-requests", map[string]any{"departmentCode": "beauty", "note": "Shea butter"})
	if asked.status != 201 || asked.json(t)["status"] != "PENDING" || asked.json(t)["needs"].([]any)[0] != "BEAUTY_LABELLING" {
		t.Fatalf("request = %d %s", asked.status, asked.body)
	}
	if r := b.do("GET", "/v1/seller/catalog-scope", nil); len(r.json(t)["requests"].([]any)) != 1 {
		t.Fatalf("scope after request = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/department-requests", map[string]any{"departmentCode": "groceries"}), 409)
	mustStatus(t, b.do("POST", "/v1/seller/department-requests", map[string]any{"departmentCode": "hardware"}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/department-requests", map[string]any{"departmentCode": "Not A Code"}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/department-requests", map[string]any{}), 422)
}
