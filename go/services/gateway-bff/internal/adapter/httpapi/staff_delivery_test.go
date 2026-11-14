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

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

const (
	dlvShipment = "6f1c2d3e-0a1b-4c5d-8e9f-a0b1c2d3e4f5"
	dlvPhoto    = "3f2b8c1e-9d4a-4b6e-8f10-2a7c5d9e1b34"
	dlvPhotoBad = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
	dlvPhotoBig = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e"
)

func dlvRow(status string) map[string]any {
	return map[string]any{"shipmentId": dlvShipment, "orderNumber": "AT-2026-DLV001", "shipmentLabel": "Shipment 1 of 1", "mode": "FULFILLED_BY_AFROTRANSACT",
		"status": status, "sellerName": "Mama Tee's Pantry", "nodeId": "HOU-FC", "pickupLocationId": nil, "customerName": "Ada Obi", "customerCity": "Houston",
		"customerState": "TX", "customerPostalCode": "77002", "units": 2, "perishable": false, "promisedBy": "2026-11-17", "assigneeName": "Segun Lawal",
		"assigneeId": "stf-9", "attempts": 0, "version": 1, "proofAttached": status == "delivered", "updatedAt": "2026-11-14T15:00:00Z", "deliveredAt": nil}
}

func dlvDetail(status string, manage bool) map[string]any {
	d := map[string]any{"row": dlvRow(status), "items": []any{map[string]any{"title": "Jollof spice mix", "quantity": 2}},
		"destination": map[string]any{"name": "Ada Obi", "line1": nil, "line2": nil, "city": "Houston", "state": "TX", "postalCode": nil, "phone": nil, "instructions": nil, "handToCustomer": false},
		"job":         map[string]any{"assigneeName": "Segun Lawal", "assignedAt": "2026-11-14T14:00:00Z", "assignedByName": "Segun Lawal", "attempts": 0, "lastFailure": nil, "version": 1},
		"proof":       nil, "audit": []any{map[string]any{"action": "TAKE", "fromStatus": "READY", "toStatus": "READY", "actorName": "Segun Lawal", "detail": map[string]any{}, "at": "2026-11-14T14:00:00Z"}},
		"nextStatuses": []any{"picked_up"}, "canManage": manage}
	if manage {
		d["destination"] = map[string]any{"name": "Ada Obi", "line1": "1 Main St", "line2": nil, "city": "Houston", "state": "TX", "postalCode": "77002", "phone": "+17135550142",
			"instructions": "Gate code 4411", "handToCustomer": false}
	}
	if status == "delivered" {
		d["proof"] = map[string]any{"photoAssetIds": []any{dlvPhoto}, "recipientName": "Ada Obi", "note": nil, "latitude": nil, "longitude": nil,
			"deliveredAt": "2026-11-14T16:00:00Z", "recordedByName": "Segun Lawal", "correctedAt": nil, "correctedByName": nil, "corrections": 0, "version": 0}
		d["nextStatuses"] = []any{}
	}
	return d
}

// deliveryOverride fakes orders' /v1/deliveries, media's uploads and assets, seller's ship-from locations and four staff members.
func deliveryOverride(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	staff := func(id, first, role string, perms ...string) {
		writeJ(w, 200, map[string]any{"id": id, "firstName": first, "lastName": "Test", "email": first + "@afrotransact.com", "principalType": "STAFF",
			"roles": []string{role}, "permissions": perms})
	}
	switch {
	case p == "/v1/me" && token == "dlv-manager":
		staff("stf-9", "Segun", "platform.dispatcher", "delivery:read", "delivery:manage")
	case p == "/v1/me" && token == "dlv-reader":
		staff("stf-4", "Amara", "platform.customer_support", "delivery:read")
	case p == "/v1/me" && token == "dlv-ops-no-permission":
		staff("stf-5", "Chidi", "platform.operations_manager", "orders:read", "orders:manage")
	case p == "/v1/me" && token == "dlv-finance":
		staff("stf-6", "Kofi", "platform.finance", "delivery:manage")
	case p == "/v1/deliveries" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"items": []any{dlvRow("ready")}, "page": 0, "size": 25, "total": 1, "counts": map[string]any{"ready": 1, "out_for_delivery": 0}})
	case p == "/v1/deliveries/couriers":
		writeJ(w, 200, []any{map[string]any{"id": "stf-9", "name": "Segun Lawal"}, map[string]any{"id": "stf-2", "name": "Chidi Nwosu"}})
	case p == "/v1/centres":
		writeJ(w, 200, []any{map[string]any{"id": "HOU-FC", "name": "Houston FC", "city": "Houston"}})
	case p == "/v1/deliveries/"+dlvShipment && r.Method == http.MethodGet:
		writeJ(w, 200, dlvDetail("ready", token == "dlv-manager"))
	case strings.HasPrefix(p, "/v1/deliveries/"+dlvShipment+"/") && strings.HasSuffix(p, "/take"):
		writeJ(w, 200, dlvDetail("ready", true))
	case strings.HasSuffix(p, "/assign") && strings.HasPrefix(p, "/v1/deliveries/"):
		problemDetail(w, 422, "orders.delivery-assignee-invalid", "That person cannot be assigned deliveries")
	case strings.HasSuffix(p, "/picked-up") && strings.HasPrefix(p, "/v1/deliveries/"):
		problemDetail(w, 409, "orders.delivery-unassigned", "Take this delivery, or assign it to someone, first")
	case strings.HasSuffix(p, "/delivered") && strings.HasPrefix(p, "/v1/deliveries/"):
		writeJ(w, 200, dlvDetail("delivered", true))
	case p == "/v1/uploads" && r.Method == http.MethodPost:
		writeJ(w, 201, map[string]any{"asset": map[string]any{"id": dlvPhoto}, "upload": map[string]any{"method": "PUT", "url": "https://uploads.example/proof", "headers": map[string]any{"Content-Type": "image/jpeg"}}})
	case strings.HasSuffix(p, "/complete") && strings.HasPrefix(p, "/v1/assets/"):
		writeJ(w, 200, map[string]any{})
	case p == "/v1/assets/"+dlvPhoto:
		writeJ(w, 200, map[string]any{"id": dlvPhoto, "purpose": "proof-photo", "contentType": "image/jpeg", "bytes": 2_000_000, "status": "READY"})
	case p == "/v1/assets/"+dlvPhotoBad:
		writeJ(w, 200, map[string]any{"id": dlvPhotoBad, "purpose": "product-image", "contentType": "image/jpeg", "bytes": 2_000_000, "status": "READY"})
	case p == "/v1/assets/"+dlvPhotoBig:
		writeJ(w, 200, map[string]any{"id": dlvPhotoBig, "purpose": "proof-photo", "contentType": "image/jpeg", "bytes": 20_000_000, "status": "READY"})
	default:
		return false
	}
	return true
}

// TestStaffDeliveryModule drives the Delivery module through the real handlers: who may read and act (module level and
// delivery:read / delivery:manage, both checked before orders is called), the proof rules (a photo is required and must be a
// proof photo of an allowed type and size), idempotency keys, and the shape of every answer against the contract.
func TestStaffDeliveryModule(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakePlatform{override: deliveryOverride}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) { staff.Delivery = app.NewStaffDeliveryService(d) })
	do := func(token, method, path string, body any, want int, headers ...string) *httptest.ResponseRecorder {
		t.Helper()
		rec := raw(token, method, path, body, headers...)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", method, path, rec.Code, rec.Body.String())
		}
		if strings.Contains(rec.Header().Get("Content-Type"), "json") {
			in := &openapi3filter.ResponseValidationInput{
				RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
					Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
				Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
				Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
			}
			if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
				t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
			}
		}
		return rec
	}
	const base = "/v1/staff/deliveries"
	one := base + "/" + dlvShipment

	// Reading: the queue names people and places and resolves the origin city; the sidebar module is in the session.
	list := jsonOf(t, do("dlv-reader", "GET", base+"?status=ready,out_for_delivery&assignee=me&q=Ada&sort=promised", nil, 200))
	rows := list["items"].([]any)
	row := rows[0].(map[string]any)
	if row["customerCity"] != "Houston" || row["originCity"] != "Houston" || row["sellerName"] != "Mama Tee's Pantry" || row["assigneeName"] != "Segun Lawal" {
		t.Fatalf("row = %+v", row)
	}
	if list["canManage"] != false {
		t.Fatalf("a reader must not be offered actions: %+v", list)
	}
	if rows := jsonOf(t, do("dlv-manager", "GET", base, nil, 200)); rows["canManage"] != true {
		t.Fatalf("manager = %+v", rows)
	}
	couriers := do("dlv-reader", "GET", base+"/couriers", nil, 200).Body.String()
	if !strings.Contains(couriers, "Segun Lawal") || !strings.Contains(couriers, "Chidi Nwosu") {
		t.Fatalf("couriers = %s", couriers)
	}
	reader := jsonOf(t, do("dlv-reader", "GET", one, nil, 200))
	if reader["canManage"] != false || len(reader["nextStatuses"].([]any)) != 0 {
		t.Fatalf("a reader sees no next steps: %+v", reader)
	}
	manager := jsonOf(t, do("dlv-manager", "GET", one, nil, 200))
	if manager["canManage"] != true || manager["destination"].(map[string]any)["line1"] != "1 Main St" {
		t.Fatalf("manager detail = %+v", manager)
	}
	session := jsonOf(t, do("dlv-manager", "GET", "/v1/staff/session", nil, 200))
	found := false
	for _, g := range session["navGroups"].([]any) {
		for _, m := range g.(map[string]any)["modules"].([]any) {
			if m.(map[string]any)["key"] == "delivery" && m.(map[string]any)["label"] == "Delivery" {
				found = true
			}
		}
	}
	if !found {
		t.Fatalf("the dispatcher's sidebar has no Delivery link: %+v", session["navGroups"])
	}

	// Permissions, server side: a reader cannot act, a finance user with the permission but no module access cannot either,
	// and orders is never called for a refused request.
	before := len(f.calls)
	do("dlv-reader", "POST", one+"/take", nil, 403)
	do("dlv-reader", "POST", one+"/picked-up", nil, 403)
	do("dlv-reader", "POST", one+"/delivered", map[string]any{"photoAssetIds": []string{dlvPhoto}}, 403)
	do("dlv-reader", "POST", base+"/uploads", map[string]any{"fileName": "p.jpg", "contentType": "image/jpeg", "size": 1000}, 403)
	do("dlv-ops-no-permission", "GET", base, nil, 403)
	do("dlv-ops-no-permission", "POST", one+"/take", nil, 403)
	do("dlv-finance", "GET", base, nil, 403)
	do("dlv-finance", "POST", one+"/take", nil, 403)
	do("customer", "GET", base, nil, 403)
	for _, c := range f.calls[before:] {
		if strings.Contains(c, "/v1/deliveries") && c != "GET /v1/me" {
			t.Fatalf("orders was called for a refused request: %s", c)
		}
	}
	do("", "GET", base, nil, 401)

	// Acting: take, the orders refusals are worded, assign needs a person.
	do("dlv-manager", "POST", one+"/take", nil, 200, "Idempotency-Key", "k-take-1")
	if !f.called("POST /v1/deliveries/" + dlvShipment + "/take") {
		t.Fatal("take did not reach orders")
	}
	do("dlv-manager", "POST", one+"/assign", map[string]any{"assigneeId": ""}, 422)
	do("dlv-manager", "POST", one+"/assign", map[string]any{"assigneeId": "stf-nobody"}, 422)
	conflict := jsonOf(t, do("dlv-manager", "POST", one+"/picked-up", map[string]any{"version": 0}, 409))
	if !strings.Contains(conflict["detail"].(string), "Take this delivery") {
		t.Fatalf("conflict = %+v", conflict)
	}
	do("dlv-manager", "POST", one+"/failed", map[string]any{"reason": " "}, 422)
	do("dlv-manager", "PUT", one+"/proof", map[string]any{"photoAssetIds": []string{dlvPhoto}, "reason": ""}, 422)

	// Proof: no photo, a malformed id, someone else's file, a file that is too big, too many photos, half a position,
	// an over-long note and recipient are all refused before orders is called.
	orders := len(f.calls)
	for name, body := range map[string]map[string]any{
		"no photo":         {"recipientName": "Ada"},
		"empty list":       {"photoAssetIds": []string{}},
		"malformed id":     {"photoAssetIds": []string{"not-an-asset"}},
		"not a proof file": {"photoAssetIds": []string{dlvPhotoBad}},
		"too big":          {"photoAssetIds": []string{dlvPhotoBig}},
		"too many": {"photoAssetIds": []string{dlvPhoto, dlvPhotoBad, dlvPhotoBig, "a0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000002",
			"a0000000-0000-4000-8000-000000000003"}},
		"half a position": {"photoAssetIds": []string{dlvPhoto}, "latitude": 29.7},
		"off the map":     {"photoAssetIds": []string{dlvPhoto}, "latitude": 129.7, "longitude": -95.3},
		"long note":       {"photoAssetIds": []string{dlvPhoto}, "note": strings.Repeat("n", 501)},
		"long recipient":  {"photoAssetIds": []string{dlvPhoto}, "recipientName": strings.Repeat("r", 121)},
	} {
		rec := do("dlv-manager", "POST", one+"/delivered", body, 422)
		if t.Failed() {
			t.Fatalf("%s: %s", name, rec.Body.String())
		}
	}
	for _, c := range f.calls[orders:] {
		if strings.HasSuffix(c, "/delivered") {
			t.Fatalf("orders was called with a bad proof: %s", c)
		}
	}

	// A good proof reaches orders normalised: lower-case ids, blank fields left out, coordinates only when sent.
	do("dlv-manager", "POST", one+"/delivered", map[string]any{"photoAssetIds": []string{strings.ToUpper(dlvPhoto)}, "recipientName": "  Ada Obi ", "note": " "}, 200, "Idempotency-Key", "k-deliver-1")
	sent := f.bodies["POST /v1/deliveries/"+dlvShipment+"/delivered"]
	if ids := sent["photoAssetIds"].([]any); len(ids) != 1 || ids[0] != dlvPhoto || sent["recipientName"] != "Ada Obi" {
		t.Fatalf("body to orders = %+v", sent)
	}
	for _, k := range []string{"note", "latitude", "longitude"} {
		if _, ok := sent[k]; ok {
			t.Fatalf("%s must be left out when not given: %+v", k, sent)
		}
	}
	do("dlv-manager", "POST", one+"/delivered", map[string]any{"photoAssetIds": []string{dlvPhoto}, "latitude": 29.76, "longitude": -95.37}, 200)
	if b := f.bodies["POST /v1/deliveries/"+dlvShipment+"/delivered"]; b["latitude"] != 29.76 || b["longitude"] != -95.37 {
		t.Fatalf("a position that was sent is passed on: %+v", b)
	}

	// Photo upload: a proof-photo ticket; other types and sizes are refused.
	ticket := jsonOf(t, do("dlv-manager", "POST", base+"/uploads", map[string]any{"fileName": "door.jpg", "contentType": "image/jpeg", "size": 2_000_000}, 201))
	if ticket["assetId"] != dlvPhoto || ticket["upload"].(map[string]any)["method"] != "PUT" {
		t.Fatalf("ticket = %+v", ticket)
	}
	if purpose := f.bodies["POST /v1/uploads"]["purpose"]; purpose != "proof-photo" {
		t.Fatalf("media purpose = %v", purpose)
	}
	do("dlv-manager", "POST", base+"/uploads", map[string]any{"fileName": "door.pdf", "contentType": "application/pdf", "size": 2_000_000}, 422)
	do("dlv-manager", "POST", base+"/uploads", map[string]any{"fileName": "door.jpg", "contentType": "image/jpeg", "size": 20_000_000}, 422)
	do("dlv-manager", "POST", base+"/uploads", map[string]any{"fileName": "../door.jpg", "contentType": "image/jpeg", "size": 2_000}, 422)
}
