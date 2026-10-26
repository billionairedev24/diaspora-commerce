package domain_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func schedule() map[string]any {
	return map[string]any{"locationId": "4b2f9d3a-0000-4000-8000-00000000000a", "windowStart": "2026-11-07T10:00:00-06:00", "windowEnd": "2026-11-07T14:00:00-06:00",
		"cutoffAt": "2026-11-05T20:00:00-06:00", "minOrders": float64(12), "capacityOrders": float64(30)}
}

func TestRunActionsFollowTheStatus(t *testing.T) {
	cases := map[string]domain.RunAllowed{
		"DRAFT":            {Edit: true, Delete: true, Announce: true, Open: true, Cancel: true},
		"ANNOUNCED":        {Edit: true, Open: true, Cancel: true},
		"OPEN":             {Edit: true, Extend: true, Confirm: true, Cancel: true},
		"EXTENDED":         {Edit: true, Extend: true, Confirm: true, Cancel: true},
		"CUT_OFF":          {Extend: true, Confirm: true, Cancel: true},
		"CONFIRMED":        {Cancel: true},
		"COLLECTING":       {},
		"OUT_FOR_DELIVERY": {},
		"COMPLETED":        {},
		"CANCELLED":        {},
		"something else":   {},
	}
	for status, want := range cases {
		if got := domain.RunActions(status); got != want {
			t.Errorf("%s: %+v, want %+v", status, got, want)
		}
	}
	if got := domain.RunActions(" open "); !got.Extend {
		t.Error("the status is read case-insensitively")
	}
}

func TestRunScheduleBodyNormalisesAndLeavesDefaultsToDeliveryRuns(t *testing.T) {
	b := schedule()
	b["notes"] = "  Cold bags  "
	b["cutoffPolicy"] = "cancel"
	b["feeMinor"] = nil
	out, err := domain.RunScheduleBody(b, true)
	if err != nil {
		t.Fatal(err)
	}
	if out["windowStart"] != "2026-11-07T16:00:00Z" || out["cutoffAt"] != "2026-11-06T02:00:00Z" || out["notes"] != "Cold bags" || out["cutoffPolicy"] != "CANCEL" {
		t.Errorf("out = %v", out)
	}
	for _, k := range []string{"feeMinor", "dropMode", "capacityPackages", "opensAt", "extendDays"} {
		if _, has := out[k]; has {
			t.Errorf("%s was sent although staff left it blank", k)
		}
	}
}

func TestRunScheduleBodyChecksEachFieldAndOrderOfDates(t *testing.T) {
	cases := map[string]struct {
		mutate func(map[string]any)
		field  string
	}{
		"window not after start": {func(b map[string]any) { b["windowEnd"] = b["windowStart"] }, "windowEnd"},
		"window over a day":      {func(b map[string]any) { b["windowEnd"] = "2026-11-08T11:00:00-06:00" }, "windowEnd"},
		"cutoff at the start":    {func(b map[string]any) { b["cutoffAt"] = b["windowStart"] }, "cutoffAt"},
		"opens at the cutoff":    {func(b map[string]any) { b["opensAt"] = b["cutoffAt"] }, "opensAt"},
		"pickup after the end":   {func(b map[string]any) { b["sellerPickupAt"] = "2026-11-08T00:00:00Z" }, "sellerPickupAt"},
		"capacity under minimum": {func(b map[string]any) { b["capacityOrders"] = float64(3) }, "capacityOrders"},
		"too many packages":      {func(b map[string]any) { b["capacityPackages"] = float64(100_001) }, "capacityPackages"},
		"note of emoji":          {func(b map[string]any) { b["notes"] = strings.Repeat("😀", 501) }, "notes"},
	}
	for name, c := range cases {
		b := schedule()
		c.mutate(b)
		_, err := domain.RunScheduleBody(b, true)
		if fieldsOf(t, err)[c.field] == "" {
			t.Errorf("%s: no problem on %s", name, c.field)
		}
	}
	// 500 characters of emoji are 500 characters, not 2000 bytes.
	b := schedule()
	b["notes"] = strings.Repeat("😀", 500)
	if _, err := domain.RunScheduleBody(b, true); err != nil {
		t.Errorf("a note of 500 characters: %v", err)
	}
}

func TestRunScheduleBodyNeedsALocationOnlyToCreate(t *testing.T) {
	b := schedule()
	delete(b, "locationId")
	if _, err := domain.RunScheduleBody(b, true); fieldsOf(t, err)["locationId"] == "" {
		t.Error("a new run needs a location")
	}
	if _, err := domain.RunScheduleBody(b, false); err != nil {
		t.Errorf("a change keeps the location: %v", err)
	}
}

func TestRunExtendBodyNeedsTheNewCutoffAndAReason(t *testing.T) {
	out, err := domain.RunExtendBody(map[string]any{"cutoffAt": "2026-10-30T01:00:00+02:00", "reason": "  Sellers need a day  "})
	if err != nil || out["cutoffAt"] != "2026-10-29T23:00:00Z" || out["reason"] != "Sellers need a day" {
		t.Fatalf("out = %v, err = %v", out, err)
	}
	_, err = domain.RunExtendBody(map[string]any{})
	if f := fieldsOf(t, err); f["cutoffAt"] == "" || f["reason"] == "" {
		t.Errorf("fields = %v", f)
	}
	_, err = domain.RunExtendBody(map[string]any{"cutoffAt": "2026-10-30T01:00:00Z", "reason": "x", "windowStart": "2026-11-01T10:00:00Z"})
	if fieldsOf(t, err)["windowEnd"] == "" {
		t.Error("half a window")
	}
}

func TestRunReasonAndOrdersQuery(t *testing.T) {
	if r, problem := domain.RunReason(map[string]any{"reason": " why "}, "missing"); r != "why" || problem != "" {
		t.Errorf("reason = %q, %q", r, problem)
	}
	if _, problem := domain.RunReason(map[string]any{}, "missing"); problem != "missing" {
		t.Errorf("problem = %q", problem)
	}
	if _, problem := domain.RunReason(map[string]any{"reason": strings.Repeat("r", 501)}, "missing"); problem == "" {
		t.Error("a reason over 500 characters")
	}
	if s, err := domain.RunOrdersQuery("cancelled"); err != nil || s != "CANCELLED" {
		t.Errorf("status = %q, %v", s, err)
	}
	if _, err := domain.RunOrdersQuery("lost"); err == nil {
		t.Error("an unknown status")
	}
}
