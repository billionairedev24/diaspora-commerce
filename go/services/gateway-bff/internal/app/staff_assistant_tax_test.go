package app

import (
	"context"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// fakeTax answers the two reads Lola's tax tools make; every other method is unused.
type fakeTax struct {
	StaffTaxService
	reviews, shadow url.Values
	denied          bool
	shadowOff       bool
}

func (f *fakeTax) Reviews(_ context.Context, _ *domain.Caller, q url.Values) (any, error) {
	f.reviews = q
	if f.denied {
		return nil, needsPermission("tax:read")
	}
	return jobj{"status": "PENDING_REVIEW", "total": 2, "items": []any{jobj{"title": "Roasted cashews, 6 oz bag", "sellers": []any{"Mama Nkechi's Kitchen"}, "categoryName": "Nuts & seeds",
		"statusLabel": "Waiting for tax review", "why": "The seller claims a tax exemption.", "proposed": map[string]any{"outcomeLabel": "Exempt (no tax)"}, "ruleVersion": "tx-1",
		"atin": "AT20000101"}}}, nil
}

func (f *fakeTax) Shadow(_ context.Context, _ *domain.Caller, q url.Values) (any, error) {
	f.shadow = q
	if f.shadowOff {
		return jobj{"shadowOff": true, "message": "Shadow mode is off."}, nil
	}
	return jobj{"providerLabel": "Stripe Tax", "from": q.Get("from"), "to": q.Get("to"), "linesCompared": 117.0, "linesDiffering": 6.0,
		"readiness":  jobj{"ready": false, "headline": "Not ready to cut over: 3 of 6 checks need work.", "checks": []any{jobj{"title": "Long enough", "ok": true, "detail": "31 days"}}},
		"byCategory": []any{jobj{"label": "Nuts seeds", "lines": 64.0, "differing": 3.0, "differingPct": 4.69}}}, nil
}

func lolaRun(f StaffTaxService) *staffRun {
	return &staffRun{s: &staffAssistantImpl{a: StaffAssistantDeps{Tax: f}}, c: &domain.Caller{}, sc: staffCtx{now: time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC)}}
}

func TestLolaTaxReviewQueueIsReadOnlyAndByName(t *testing.T) {
	f := &fakeTax{}
	out, err := staffToolTaxReviews(context.Background(), lolaRun(f), map[string]any{"limit": 3.0, "query": "cashew"})
	if err != nil {
		t.Fatal(err)
	}
	if f.reviews.Get("size") != "3" || f.reviews.Get("q") != "cashew" {
		t.Errorf("query = %v", f.reviews)
	}
	rows := out.(map[string]any)["variants"].([]map[string]any)
	if len(rows) != 1 || rows[0]["product"] != "Roasted cashews, 6 oz bag" || rows[0]["proposedTax"] != "Exempt (no tax)" {
		t.Errorf("rows = %v", rows)
	}
	for _, v := range rows[0] {
		if s, ok := v.(string); ok && strings.HasPrefix(s, "AT2000") {
			t.Errorf("an id reached Lola: %v", rows[0])
		}
	}
	if _, err := staffToolTaxReviews(context.Background(), lolaRun(&fakeTax{denied: true}), nil); err == nil {
		t.Error("a member without tax:read was answered")
	}
	if _, err := staffToolTaxReviews(context.Background(), lolaRun(nil), nil); err == nil {
		t.Error("no tax service should read as unavailable")
	}
}

func TestLolaTaxShadowReportSummarisesReadiness(t *testing.T) {
	f := &fakeTax{}
	out, err := staffToolTaxShadow(context.Background(), lolaRun(f), map[string]any{"days": 7.0})
	if err != nil {
		t.Fatal(err)
	}
	m := out.(map[string]any)
	if f.shadow.Get("from") != "2026-11-02" || f.shadow.Get("to") != "2026-11-09" {
		t.Errorf("window = %v", f.shadow)
	}
	if m["readyToCutOver"] != false || !strings.HasPrefix(m["summary"].(string), "Not ready to cut over") || len(m["checks"].([]map[string]any)) != 1 {
		t.Errorf("summary = %v", m)
	}
	off, err := staffToolTaxShadow(context.Background(), lolaRun(&fakeTax{shadowOff: true}), nil)
	if err != nil || off.(map[string]any)["shadowMode"] != "off" {
		t.Errorf("shadow off = %v %v", off, err)
	}
}

func TestLolaTaxToolsAreOffered(t *testing.T) {
	names := map[string]bool{}
	for _, def := range staffTools {
		names[def.name] = true
	}
	for _, want := range []string{domain.StaffToolTaxReviews, domain.StaffToolTaxShadow} {
		if !names[want] {
			t.Errorf("%s is not a tool", want)
		}
	}
}
