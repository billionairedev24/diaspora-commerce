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

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestSellerFbaMirrorsInventoryForTheActiveOrg covers Send to AfroTransact (S281): lists are filtered to the active
// organisation, ownerOrg from the browser is never relayed, labels stream as PDF, unknown routes are 404, and every
// JSON answer matches gateway-bff-seller.yaml.
func TestSellerFbaMirrorsInventoryForTheActiveOrg(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(sellerContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	validated := map[string]int{}
	_, b := sellerSetup(t, testkit.Options{})
	b.onResponse = func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			return
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", req.Method, req.URL.Path, rec.Code, rec.Body.Bytes())
			return
		}
		if !strings.Contains(rec.Header().Get("Content-Type"), "json") {
			validated[route.Operation.OperationID]++
			return
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", req.Method, req.URL.Path, rec.Code, err, rec.Body.Bytes())
			return
		}
		validated[route.Operation.OperationID]++
	}

	list := b.do("GET", "/v1/seller/fba/inbound?status=WORKING&ownerOrg=org_someone_else", nil)
	mustStatus(t, list, 200)
	items := list.json(t)["items"].([]any)
	line := items[0].(map[string]any)["lines"].([]any)[0].(map[string]any)
	if line["lotNo"] != "B-77" || line["casePack"] != float64(6) {
		t.Fatalf("plan line lost its batch: %v", line)
	}
	created := b.do("POST", "/v1/seller/fba/inbound", map[string]any{"ownerOrg": "org_someone_else", "nodeId": "ATL-FC",
		"lines": []map[string]any{{"atin": "AT00000042", "qty": 12, "casePack": 6, "lotNo": "B-77", "expDate": "2027-09-01"}}})
	mustStatus(t, created, 201)
	mustStatus(t, b.do("GET", "/v1/seller/fba/inbound/7d4a3c2e-0000-4000-8000-000000000001", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/fba/inbound/7d4a3c2e-0000-4000-8000-000000000001/ship", nil), 200)
	labels := b.do("GET", "/v1/seller/fba/inbound/7d4a3c2e-0000-4000-8000-000000000001/labels?kind=BOX&format=PDF", nil)
	mustStatus(t, labels, 200)
	if ct := labels.header.Get("Content-Type"); ct != "application/pdf" {
		t.Fatalf("labels Content-Type = %q", ct)
	}
	lots := b.do("GET", "/v1/seller/fba/lots?sku=ATF1234567", nil)
	mustStatus(t, lots, 200)
	exerciseSellerFba(t, b)
	mustStatus(t, b.do("GET", "/v1/seller/fba/receipts", nil), 404)
	mustStatus(t, b.do("POST", "/v1/seller/fba/inbound/x/receipts", map[string]any{}), 404)
	for _, op := range []string{"getSellerFbaInbound", "postSellerFbaInbound", "getSellerFbaInboundById", "postSellerFbaInboundShip",
		"getSellerFbaInboundLabels", "getSellerFbaLots"} {
		if validated[op] == 0 {
			t.Errorf("%s was not exercised", op)
		}
	}
}

// exerciseSellerFba calls every Send to AfroTransact route once (the seller conformance test validates them all).
func exerciseSellerFba(t *testing.T, b *browser) {
	t.Helper()
	const plan = "/v1/seller/fba/inbound/7d4a3c2e-0000-4000-8000-000000000001"
	mustStatus(t, b.do("GET", "/v1/seller/fba/nodes", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/fba/inventory", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/fba/inbound", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/fba/inbound", map[string]any{"lines": []map[string]any{{"atin": "AT00000042", "qty": 12}}}), 201)
	mustStatus(t, b.do("GET", plan, nil), 200)
	mustStatus(t, b.do("PUT", plan, map[string]any{"lines": []map[string]any{{"atin": "AT00000042", "qty": 18, "casePack": 6}}}), 200)
	mustStatus(t, b.do("PUT", plan+"/tracking", map[string]any{"carrier": "ups", "trackingNumber": "1Z999AA10123456784"}), 200)
	mustStatus(t, b.do("POST", plan+"/ship", nil), 200)
	mustStatus(t, b.do("POST", plan+"/cancel", nil), 200)
	mustStatus(t, b.do("GET", plan+"/labels?kind=UNIT", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/fba/lots", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/fba/lots/markdown", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/fba/lots/5b1d0c9e-0000-4000-8000-000000000002", nil), 200)
}
