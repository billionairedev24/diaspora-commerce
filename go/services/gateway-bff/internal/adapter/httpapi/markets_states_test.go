package httpapi_test

import (
	"testing"
)

// Markets & locations: a country's states are switched on and off, and one state's capabilities changed, from the
// country itself (content's draft, PATCH per region).
func TestMarketsStatesAreEditedFromTheCountry(t *testing.T) {
	f := &fakePlatform{bodies: map[string]map[string]any{}}
	do := staffBFF(t, f)

	if rec := do("admin", "POST", "/v1/staff/resources/markets/CA/actions/states", map[string]any{"values": map[string]any{"live": []string{"ON"}}}); rec.Code != 200 {
		t.Fatalf("states = %d %s", rec.Code, rec.Body.String())
	}
	if b := f.bodies["PATCH /v1/markets/draft/countries/CA/regions/ON"]; b["marketplace"] != true || len(b) != 1 {
		t.Fatalf("switch ON body = %v (calls %v)", b, f.calls)
	}
	if rec := do("admin", "POST", "/v1/staff/resources/markets/CA/actions/state-switches", map[string]any{"values": map[string]any{"region": "ON", "food": "on", "pickup": "off"}}); rec.Code != 200 {
		t.Fatalf("state switches = %d %s", rec.Code, rec.Body.String())
	}
	if b := f.bodies["PATCH /v1/markets/draft/countries/CA/regions/ON"]; b["food"] != true || b["pickup"] != false || b["marketplace"] != nil {
		t.Fatalf("switches body = %v", b)
	}
	if rec := do("admin", "POST", "/v1/staff/resources/markets/CA/actions/state-switches", map[string]any{"values": map[string]any{"region": "QC", "food": "on"}}); rec.Code != 422 {
		t.Fatalf("unknown region = %d", rec.Code)
	}
	if rec := do("admin", "POST", "/v1/staff/resources/markets/CA/actions/state-switches", map[string]any{"values": map[string]any{"region": "ON"}}); rec.Code != 422 {
		t.Fatalf("no change = %d", rec.Code)
	}
	if rec := do("support", "POST", "/v1/staff/resources/markets/CA/actions/states", map[string]any{"values": map[string]any{"live": []string{}}}); rec.Code != 403 {
		t.Fatalf("support = %d", rec.Code)
	}
}
