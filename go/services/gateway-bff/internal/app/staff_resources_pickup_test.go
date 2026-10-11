package app

import (
	"testing"
)

func TestPickupLocationFormRoundTrip(t *testing.T) {
	body, err := pickupBody(jobj{"name": "Houston FC — Will Call", "kind": "LOCKER", "line1": "12000 Bissonnet St", "city": "Houston", "state": "tx",
		"postalCode": "77099", "timezone": "America/Chicago", "days": "MON_FRI", "opens": "08:00", "closes": "20:00", "readyHours": float64(2),
		"latitude": 29.67, "longitude": -95.55, "active": true})
	if err != nil {
		t.Fatal(err)
	}
	hours := body["hours"].([]jobj)
	if body["ownerOrg"] != platformPickupOwner || body["kind"] != "LOCKER" || len(hours) != 5 || hours[0]["weekday"] != 1 || hours[0]["open"] != "08:00" {
		t.Fatalf("body = %+v", body)
	}
	if a := body["address"].(jobj); a["state"] != "TX" || a["country"] != "US" {
		t.Fatalf("address = %+v", a)
	}
	if p := body["position"].(jobj); p["lat"] != 29.67 {
		t.Fatalf("position = %+v", p)
	}

	// What carrier-gateway returns reads back into the same form values.
	stored := jobj{"hours": []any{}}
	for _, h := range hours {
		stored["hours"] = append(stored["hours"].([]any), map[string]any{"weekday": float64(h["weekday"].(int)), "open": h["open"], "close": h["close"]})
	}
	if days, opens, closes := pickupDaysOf(stored); days != "MON_FRI" || opens != "08:00" || closes != "20:00" {
		t.Fatalf("days = %s %s %s", days, opens, closes)
	}

	if _, err := pickupBody(jobj{"kind": "SELLER", "opens": "18:00", "closes": "09:00"}); err == nil {
		t.Fatal("closing before opening must be refused")
	}
	if body, _ := pickupBody(jobj{"kind": "SELLER", "opens": "09:00", "closes": "18:00"}); body["kind"] != "PLATFORM_COUNTER" {
		t.Fatalf("staff create platform points only: %v", body["kind"])
	}
	if _, ok := staffResources()["pickup-locations"]; !ok {
		t.Fatal("pickup-locations is not registered")
	}
}
