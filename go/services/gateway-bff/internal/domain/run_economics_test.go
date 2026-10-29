package domain_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func problemFields(t *testing.T, err error) map[string]string {
	t.Helper()
	var p *domain.Problem
	if !errors.As(err, &p) || p.Errors == nil {
		t.Fatalf("not a validation problem: %v", err)
	}
	return p.Errors
}

func economicsBody() map[string]any {
	return map[string]any{"driverCostPerHourMinor": float64(2500), "driverCostPerMileMinor": float64(70), "collectionMinutes": float64(45),
		"perStopMinutes": 6.5, "pickupPointStopMinutes": float64(30), "handoverMinutesPerOrder": float64(1), "roundTrip": true}
}

func TestEconomicsSettingsBodyKeepsOnlyTheNumbersAndChecksEachOne(t *testing.T) {
	b := economicsBody()
	b["reason"] = "ignored here"
	b["version"] = 9
	out, err := domain.EconomicsSettingsBody(b)
	if err != nil || len(out) != 7 || out["perStopMinutes"] != 6.5 || out["roundTrip"] != true {
		t.Fatalf("%v %v", out, err)
	}
	for field, bad := range map[string]any{"driverCostPerHourMinor": float64(100_001), "driverCostPerMileMinor": float64(-1), "collectionMinutes": float64(1441),
		"perStopMinutes": "six", "pickupPointStopMinutes": nil, "handoverMinutesPerOrder": float64(-0.5), "roundTrip": "yes"} {
		b := economicsBody()
		b[field] = bad
		if _, ok := problemFields(t, func() error { _, err := domain.EconomicsSettingsBody(b); return err }())[field]; !ok {
			t.Errorf("%s = %v was accepted", field, bad)
		}
	}
	if _, err := domain.EconomicsSettingsBody(map[string]any{}); len(problemFields(t, err)) != 7 {
		t.Fatalf("an empty body reports every field: %v", err)
	}
}

func TestEconomicsSettingsBodyTakesTheOptionalPlatformFeeEstimate(t *testing.T) {
	b := economicsBody()
	b["platformFeePerOrderMinor"] = float64(250)
	out, err := domain.EconomicsSettingsBody(b)
	if err != nil || len(out) != 8 || out["platformFeePerOrderMinor"] != int64(250) {
		t.Fatalf("%v %v", out, err)
	}
	b["platformFeePerOrderMinor"] = nil
	if out, err := domain.EconomicsSettingsBody(b); err != nil || len(out) != 7 {
		t.Fatalf("null is not configured: %v %v", out, err)
	}
	for _, bad := range []any{float64(-1), float64(100_001), "two", float64(2.5)} {
		b["platformFeePerOrderMinor"] = bad
		if _, ok := problemFields(t, func() error { _, err := domain.EconomicsSettingsBody(b); return err }())["platformFeePerOrderMinor"]; !ok {
			t.Errorf("%v was accepted", bad)
		}
	}
}

func TestWindowPlanBodyTakesTheCapacityForTheMarginAtCapacity(t *testing.T) {
	out, err := domain.WindowPlanBody(map[string]any{"targets": []any{"US/TX/Bastrop"}, "capacityOrders": float64(40)})
	if err != nil || out["capacityOrders"] != int64(40) {
		t.Fatalf("%v %v", out, err)
	}
	out, err = domain.WindowPlanBody(map[string]any{"targets": []any{"US/TX/Bastrop"}, "capacityOrders": nil})
	if _, has := out["capacityOrders"]; err != nil || has {
		t.Fatalf("an unset capacity stays unset: the location's default applies: %v %v", out, err)
	}
	for _, bad := range []any{float64(0), float64(10_001), "many", float64(1.5)} {
		_, err := domain.WindowPlanBody(map[string]any{"targets": []any{"US/TX/Bastrop"}, "capacityOrders": bad})
		if _, ok := problemFields(t, err)["capacityOrders"]; !ok {
			t.Errorf("%v was accepted", bad)
		}
	}
}

func TestWindowPlanBody(t *testing.T) {
	out, err := domain.WindowPlanBody(map[string]any{"targets": []any{"US/TX/Bastrop"}, "door": true, "minOrders": float64(12), "doorFeeMinor": nil})
	if err != nil || out["minOrders"] != int64(12) || out["door"] != true || len(out["targets"].([]string)) != 1 {
		t.Fatalf("%v %v", out, err)
	}
	if _, has := out["doorFeeMinor"]; has {
		t.Fatal("an unset fee stays unset: the fee rules decide")
	}
	if _, err := domain.WindowPlanBody(map[string]any{"locationId": "4b2f9d3a-0000-4000-8000-00000000000a"}); err != nil {
		t.Fatalf("a location alone is enough: %v", err)
	}
	for name, body := range map[string]map[string]any{"nothing": {}, "place": {"targets": []any{"Texas"}}, "region only": {"targets": []any{"us/tx"}},
		"location": {"locationId": "x"}, "min": {"locationId": "4b2f9d3a-0000-4000-8000-00000000000a", "minOrders": float64(-1)}} {
		if _, err := domain.WindowPlanBody(body); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}
	// Country and region keys are valid places for a plan too (the first city target decides the location).
	if _, err := domain.WindowPlanBody(map[string]any{"targets": []any{"US/TX"}}); err != nil {
		t.Fatalf("a region is a place: %v", err)
	}
}

func TestAdvertorialBody(t *testing.T) {
	good := map[string]any{"source": "window", "sourceId": "win-7", "headline": " Hello ", "ctaLabel": "Go", "variant": "B", "weight": float64(3),
		"startsAt": "2026-10-20T10:00:00+02:00"}
	out, err := domain.AdvertorialBody(good, true)
	if err != nil {
		t.Fatal(err)
	}
	if out["source"] != "WINDOW" || out["headline"] != "Hello" || out["body"] != "" || out["startsAt"] != "2026-10-20T08:00:00Z" || out["endsAt"] != nil {
		t.Fatalf("%v", out)
	}
	if _, err := domain.AdvertorialBody(map[string]any{"headline": "x", "ctaLabel": "y"}, false); err != nil {
		t.Fatalf("an edit needs no source: %v", err)
	}
	if f := problemFields(t, func() error {
		_, err := domain.AdvertorialBody(map[string]any{"headline": "x", "ctaLabel": "y"}, true)
		return err
	}()); f["source"] == "" || f["sourceId"] == "" {
		t.Fatalf("a new one needs its source: %v", f)
	}
	long := strings.Repeat("é", 141)
	if f := problemFields(t, func() error {
		_, err := domain.AdvertorialBody(map[string]any{"headline": long, "ctaLabel": ""}, false)
		return err
	}()); f["headline"] == "" || f["ctaLabel"] == "" {
		t.Fatalf("limits count characters, not bytes: %v", f)
	}
	if _, err := domain.AdvertorialBody(map[string]any{"headline": strings.Repeat("é", 140), "ctaLabel": "go"}, false); err != nil {
		t.Fatalf("140 characters fit: %v", err)
	}
}

func TestListQueriesAndIds(t *testing.T) {
	if q, err := domain.RunListQuery("", "open, Confirmed", "20"); err != nil || q["status"] != "OPEN,CONFIRMED" || q["limit"] != "20" {
		t.Fatalf("%v %v", q, err)
	}
	for _, c := range [][3]string{{"nope", "", ""}, {"", "gone", ""}, {"", "", "0"}, {"", "", "501"}, {"", "", "1x"}} {
		if _, err := domain.RunListQuery(c[0], c[1], c[2]); err == nil {
			t.Errorf("%v was accepted", c)
		}
	}
	if q, err := domain.AdvertorialListQuery("run", "run-1", "live", "5"); err != nil || q["source"] != "RUN" || q["status"] != "LIVE" {
		t.Fatalf("%v %v", q, err)
	}
	if _, err := domain.AdvertorialListQuery("boat", "", "", ""); err == nil {
		t.Fatal("unknown source")
	}
	if domain.ValidUUID("4b2f9d3a-0000-4000-8000-00000000000a") != nil || domain.ValidUUID("4b2f9d3a") == nil {
		t.Fatal("uuid check")
	}
	if a, ok := domain.ValidAdvertorialAction(" Publish "); !ok || a != "publish" {
		t.Fatalf("%q %v", a, ok)
	}
	if _, ok := domain.ValidAdvertorialAction("delete"); ok {
		t.Fatal("delete is not an action")
	}
}
