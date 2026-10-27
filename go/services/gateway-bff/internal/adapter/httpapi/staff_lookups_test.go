package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
)

// TestStaffLookupPickers drives the searchable pickers of the console's forms (Assign driver, Stripe lookup): drivers
// and sellers found by name, each form field marked as a lookup, and access that follows the module of the form.
func TestStaffLookupPickers(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakePlatform{payoutSt: "REQUESTED"}
	var userQueries []string
	f.override = func(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
		if r.URL.Path == "/v1/admin/users" && r.Method == http.MethodGet {
			userQueries = append(userQueries, r.URL.RawQuery)
			writeJ(w, 200, map[string]any{"items": []any{
				map[string]any{"id": "cus-1", "name": "Amara Nwosu", "email": "amara@example.com", "principalType": "CUSTOMER"},
				map[string]any{"id": "drv-1", "name": "Tunde Adeyemi", "email": "tunde@drivers.example", "principalType": "DRIVER"},
				map[string]any{"id": "drv-2", "name": "", "email": "chinedu@drivers.example", "principalType": "DRIVER"}}, "page": 0, "size": 50, "total": 3})
			return true
		}
		return false
	}
	raw := staffBFF(t, f)
	validated := map[string]int{}
	do := func(token, path string, want int) map[string]any {
		t.Helper()
		rec := raw(token, "GET", path, nil)
		if rec.Code != want {
			t.Fatalf("GET %s as %s = %d, want %d: %s", path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), "GET", "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s is not in the contract: %v", path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s answered %d, which the contract does not document", path, rec.Code)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s → %d does not match the contract: %v\nbody: %s", path, rec.Code, err, rec.Body.String())
		}
		validated[route.Operation.OperationID]++
		return jsonOf(t, rec)
	}

	// Drivers: only drivers are offered, named (an unnamed one by email), with their email on a second line.
	drivers := do("admin", "/v1/staff/lookups/drivers?q=tun", 200)["items"].([]any)
	if len(drivers) != 2 || drivers[0].(map[string]any)["id"] != "drv-1" || drivers[0].(map[string]any)["name"] != "Tunde Adeyemi" ||
		drivers[1].(map[string]any)["name"] != "chinedu@drivers.example" {
		t.Fatalf("drivers = %v", drivers)
	}
	if len(userQueries) == 0 || !strings.Contains(userQueries[0], "q=tun") {
		t.Fatalf("people query = %v", userQueries)
	}
	do("admin", "/v1/staff/lookups/drivers", 200)

	// Sellers: by name, never the retail account's id typed by hand.
	sellers := do("admin", "/v1/staff/lookups/sellers?q=kente", 200)["items"].([]any)
	if len(sellers) != 1 || sellers[0].(map[string]any)["id"] != "org-1" || sellers[0].(map[string]any)["name"] != "Kente Naturals" {
		t.Fatalf("sellers = %v", sellers)
	}
	if all := do("admin", "/v1/staff/lookups/sellers", 200)["items"].([]any); len(all) != 3 {
		t.Fatalf("every seller = %v", all)
	}
	if none := do("admin", "/v1/staff/lookups/sellers?q=zzz", 200)["items"].([]any); len(none) != 0 {
		t.Fatalf("no match = %v", none)
	}

	// Refusals: a kind that does not exist, a one-letter search, too long, no sign-in, and the wrong kind of account.
	do("admin", "/v1/staff/lookups/orders?q=ab", 404)
	do("admin", "/v1/staff/lookups/drivers?q=t", 422)
	do("admin", "/v1/staff/lookups/sellers?q="+strings.Repeat("q", 121), 422)
	do("", "/v1/staff/lookups/drivers?q=tun", 401)
	do("auditor", "/v1/staff/lookups/drivers?q=tun", 200)    // View is enough to search
	do("customer", "/v1/staff/lookups/sellers?q=kente", 403) // not staff

	for _, op := range []string{"searchStaffLookup"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
