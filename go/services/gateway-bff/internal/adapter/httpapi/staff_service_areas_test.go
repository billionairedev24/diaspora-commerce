package httpapi_test

import (
	"net/http/httptest"
	"strings"
	"testing"
)

// Settings > Markets & locations > Service areas: the radius rules and the metro zones, every change with a reason.
func TestStaffServiceAreaSettings(t *testing.T) {
	f := &fakePlatform{payoutSt: "REQUESTED"}
	raw := staffBFF(t, f)
	do := func(token, method, path string, body any, want int) *httptest.ResponseRecorder {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s = %d, want %d: %s", method, path, rec.Code, want, rec.Body.String())
		}
		return rec
	}

	rules := jsonOf(t, do("admin", "GET", "/v1/staff/resources/service-area-rules", nil, 200))
	rows := rules["rows"].([]any)
	if len(rows) != 4 { // hair & beauty, catering, any other category, platform limits
		t.Fatalf("rule rows = %v", rows)
	}
	cells := rows[0].(map[string]any)["cells"].(map[string]any)
	if cells["name"] != "Catering" && cells["name"] != "Hair & beauty" {
		t.Fatalf("first rule = %v", cells)
	}

	// A change without a reason is refused; with one it goes to bookings as the whole policy plus the reason.
	do("admin", "PATCH", "/v1/staff/resources/service-area-rules/catering", map[string]any{"values": map[string]any{"defaultMiles": 45, "minMiles": 10, "maxMiles": 100}}, 422)
	do("admin", "PATCH", "/v1/staff/resources/service-area-rules/catering", map[string]any{"values": map[string]any{"defaultMiles": 45, "minMiles": 10, "maxMiles": 100, "reason": "Caterers asked for a wider default"}}, 200)
	if b := f.bodies["PUT /v1/staff/service-areas/policy"]; b["reason"] != "Caterers asked for a wider default" {
		t.Fatalf("policy change = %v", b)
	}
	do("admin", "PATCH", "/v1/staff/resources/service-area-rules/_limits", map[string]any{"values": map[string]any{"customerTravelMiles": 35, "nearestMaxMiles": 50, "reason": "Shorter fallback"}}, 200)

	zones := jsonOf(t, do("admin", "GET", "/v1/staff/resources/service-zones", nil, 200))
	zrow := zones["rows"].([]any)[0].(map[string]any)
	if zrow["id"] != "greater-austin" || zrow["cells"].(map[string]any)["cityCount"] != float64(2) {
		t.Fatalf("zone row = %v", zrow)
	}
	sheet := jsonOf(t, do("admin", "GET", "/v1/staff/resources/service-zones/greater-austin", nil, 200))
	if !strings.Contains(sheet["title"].(string), "Greater Austin") {
		t.Fatalf("zone sheet = %v", sheet)
	}
	do("admin", "PATCH", "/v1/staff/resources/service-zones/greater-austin", map[string]any{"values": map[string]any{"name": "Greater Austin", "cities": "Georgetown, TX\nHutto, TX", "reason": "Add Hutto"}}, 200)
	do("admin", "PATCH", "/v1/staff/resources/service-zones/greater-austin", map[string]any{"values": map[string]any{"name": "Greater Austin", "cities": "Georgetown", "reason": "Bad line"}}, 422)
	do("admin", "PATCH", "/v1/staff/resources/service-zones/greater-austin", map[string]any{"values": map[string]any{"name": "Greater Austin", "cities": "Georgetown, TX"}}, 422)
	// Creating a zone sends it to bookings with the reason (the fake does not keep it, so the reload after it is a 404 here).
	raw("admin", "POST", "/v1/staff/resources/service-zones", map[string]any{"values": map[string]any{"key": "san-antonio", "name": "San Antonio", "cities": "San Antonio, TX\nNew Braunfels, TX", "reason": "New metro"}})
	if b := f.bodies["PUT /v1/staff/service-areas/zones/san-antonio"]; b["reason"] != "New metro" || len(b["zone"].(map[string]any)["cities"].([]any)) != 2 {
		t.Fatalf("zone create = %v", b)
	}
}
