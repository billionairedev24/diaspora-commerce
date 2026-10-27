package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
)

// TestStaffRunLocationsConformance drives every delivery-run location operation of S262 (create, change, switch off with
// an audited reason, switch on) through the BFF against the fake delivery-runs and validates each answer against
// gateway-bff-staff.yaml. Marketing and Operations change; Support and Finance only read.
func TestStaffRunLocationsConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := preorderStaffBFF(t)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int) *httptest.ResponseRecorder {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
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
		validated[route.Operation.OperationID]++
		return rec
	}
	sent := func(key string) map[string]any { f.mu.Lock(); defer f.mu.Unlock(); return f.bodies[key] }
	tried := func(key string) int { return calls(f, key) }
	problems := func(rec *httptest.ResponseRecorder) map[string]any {
		t.Helper()
		e, _ := jsonOf(t, rec)["errors"].(map[string]any)
		if e == nil {
			t.Fatalf("no field errors: %s", rec.Body.String())
		}
		return e
	}
	base := "/v1/staff/delivery-runs/locations"
	one := base + "/" + runLocationID
	missing := base + "/4b2f9d3a-0000-4000-8000-0000000000fe"
	town := func() map[string]any {
		return map[string]any{"name": "  Bastrop ", "market": "us", "state": "tx", "timeZone": "America/Chicago", "areaKind": "radius", "radiusMiles": 12,
			"centerLat": 30.1105, "centerLng": -97.3153, "defaultFeeMinor": 799, "defaultMinOrders": 10, "defaultCapacityOrders": 40,
			"whatsappInviteUrl": "https://chat.whatsapp.com/AbCdEf123"}
	}

	// ---- create: the web address is made from the name and state, text is trimmed, the market upper-cased ----
	created := jsonOf(t, do("ops", "POST", base, town(), 201))
	if created["name"] != "Bastrop" || created["active"] != true || created["can"] == nil {
		t.Fatalf("created = %v", created)
	}
	got := sent("POST /v1/locations")
	if got["slug"] != "bastrop-tx" || got["market"] != "US" || got["state"] != "TX" || got["areaKind"] != "RADIUS" || got["radiusMiles"] != float64(12) || got["name"] != "Bastrop" {
		t.Fatalf("create relayed %v", got)
	}
	drawn := town()
	drawn["areaKind"], drawn["geoZoneId"], drawn["slug"], drawn["active"] = "POLYGON", "zone-bastrop", "bastrop-central", false
	delete(drawn, "radiusMiles")
	do("marketing", "POST", base, drawn, 201)
	if got := sent("POST /v1/locations"); got["geoZoneId"] != "zone-bastrop" || got["slug"] != "bastrop-central" || got["active"] != false {
		t.Fatalf("polygon create relayed %v", got)
	}
	taken := town()
	taken["slug"] = "taken-tx"
	do("ops", "POST", base, taken, 409)

	before := tried("POST /v1/locations")
	for name, c := range map[string]struct {
		mutate func(map[string]any)
		field  string
	}{
		"no name":            {func(b map[string]any) { delete(b, "name") }, "name"},
		"blank name":         {func(b map[string]any) { b["name"] = "  " }, "name"},
		"long name":          {func(b map[string]any) { b["name"] = strings.Repeat("n", 121) }, "name"},
		"no market":          {func(b map[string]any) { delete(b, "market") }, "market"},
		"bad slug":           {func(b map[string]any) { b["slug"] = "Bastrop TX" }, "slug"},
		"bad time zone":      {func(b map[string]any) { b["timeZone"] = "Mars/Olympus" }, "timeZone"},
		"no area":            {func(b map[string]any) { delete(b, "areaKind") }, "areaKind"},
		"unknown area":       {func(b map[string]any) { b["areaKind"] = "SQUARE" }, "areaKind"},
		"no radius":          {func(b map[string]any) { delete(b, "radiusMiles") }, "radiusMiles"},
		"huge radius":        {func(b map[string]any) { b["radiusMiles"] = 101 }, "radiusMiles"},
		"zero radius":        {func(b map[string]any) { b["radiusMiles"] = 0 }, "radiusMiles"},
		"no centre":          {func(b map[string]any) { delete(b, "centerLat") }, "centerLat"},
		"centre off earth":   {func(b map[string]any) { b["centerLng"] = 181 }, "centerLng"},
		"polygon, no area":   {func(b map[string]any) { b["areaKind"] = "POLYGON" }, "geoZoneId"},
		"negative fee":       {func(b map[string]any) { b["defaultFeeMinor"] = -1 }, "defaultFeeMinor"},
		"dear fee":           {func(b map[string]any) { b["defaultFeeMinor"] = 20_000 }, "defaultFeeMinor"},
		"zero minimum":       {func(b map[string]any) { b["defaultMinOrders"] = 0 }, "defaultMinOrders"},
		"capacity under min": {func(b map[string]any) { b["defaultCapacityOrders"] = 5 }, "defaultCapacityOrders"},
		"bad currency":       {func(b map[string]any) { b["currency"] = "dollars" }, "currency"},
		"bad policy":         {func(b map[string]any) { b["defaultCutoffPolicy"] = "SHRUG" }, "defaultCutoffPolicy"},
		"bad drop mode":      {func(b map[string]any) { b["dropMode"] = "DRONE" }, "dropMode"},
		"link not a link":    {func(b map[string]any) { b["whatsappInviteUrl"] = "join us" }, "whatsappInviteUrl"},
		"link as number":     {func(b map[string]any) { b["whatsappInviteUrl"] = 7 }, "whatsappInviteUrl"},
	} {
		b := town()
		c.mutate(b)
		if e := problems(do("ops", "POST", base, b, 422)); e[c.field] == nil {
			t.Errorf("%s: errors = %v, want %s", name, e, c.field)
		}
	}
	if tried("POST /v1/locations") != before {
		t.Error("an invalid location reached delivery-runs")
	}
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", base, town(), 403)
	}
	do("", "POST", base, town(), 401)

	// ---- change: never switches the location, a blank link clears it ----
	edit := town()
	edit["name"] = "Bastrop County"
	edit["whatsappInviteUrl"] = ""
	edit["defaultMinOrders"] = 12
	edited := jsonOf(t, do("marketing", "PUT", one, edit, 200))
	if edited["name"] != "Bastrop County" || edited["active"] != true {
		t.Fatalf("edited = %v", edited)
	}
	if got := sent("PUT /v1/locations/" + runLocationID); got["whatsappInviteUrl"] != nil || got["defaultMinOrders"] != float64(12) {
		t.Fatalf("update relayed %v", got)
	}
	if _, has := sent("PUT /v1/locations/" + runLocationID)["active"]; has {
		t.Error("an edit tried to switch the location")
	}
	bad := town()
	bad["defaultMinOrders"] = 0
	if e := problems(do("ops", "PUT", one, bad, 422)); e["defaultMinOrders"] == nil {
		t.Errorf("errors = %v", e)
	}
	do("ops", "PUT", missing, town(), 404)
	do("ops", "PUT", base+"/nope", town(), 404)
	do("finance", "PUT", one, town(), 403)
	do("", "PUT", one, town(), 401)

	// ---- switch off: a reason is required and audited upstream; switch on ----
	off := tried("POST /v1/locations/" + runLocationID + "/deactivate")
	for _, body := range []map[string]any{nil, {}, {"reason": "   "}, {"reason": strings.Repeat("r", 501)}} {
		if e := problems(do("ops", "POST", one+"/deactivate", body, 422)); e["reason"] == nil {
			t.Errorf("deactivate %v: errors = %v", body, e)
		}
	}
	if tried("POST /v1/locations/"+runLocationID+"/deactivate") != off {
		t.Error("a switch off without a reason reached delivery-runs")
	}
	switched := jsonOf(t, do("marketing", "POST", one+"/deactivate", map[string]any{"reason": "  Driver left town  "}, 200))
	if switched["active"] != false {
		t.Fatalf("deactivated = %v", switched)
	}
	if got := sent("POST /v1/locations/" + runLocationID + "/deactivate"); got["reason"] != "Driver left town" {
		t.Fatalf("deactivate relayed %v", got)
	}
	do("ops", "POST", one+"/deactivate", map[string]any{"reason": "live"}, 409)
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", one+"/deactivate", map[string]any{"reason": "x"}, 403)
		do(token, "POST", one+"/activate", nil, 403)
	}
	do("ops", "POST", base+"/nope/deactivate", map[string]any{"reason": "x"}, 404)
	do("", "POST", one+"/deactivate", map[string]any{"reason": "x"}, 401)
	if on := jsonOf(t, do("ops", "POST", one+"/activate", nil, 200)); on["active"] != true {
		t.Errorf("activate = %v", on)
	}
	do("ops", "POST", one+"/activate", map[string]any{"reason": "Back in service"}, 200)
	if got := sent("POST /v1/locations/" + runLocationID + "/activate"); got["reason"] != "Back in service" {
		t.Errorf("activate relayed %v", got)
	}
	do("ops", "POST", missing+"/activate", nil, 404)
	do("ops", "POST", one+"/activate", map[string]any{"reason": strings.Repeat("r", 501)}, 422)
	do("", "POST", one+"/activate", nil, 401)

	for _, op := range []string{"createStaffRunLocation", "updateStaffRunLocation", "deactivateStaffRunLocation", "activateStaffRunLocation"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
