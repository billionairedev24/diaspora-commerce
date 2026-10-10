package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestStockProblem(t *testing.T) {
	p := domain.StockProblem("Jollof", 3, 3, 4)
	if p.Status != 409 || p.Code != domain.CodeStockLimit || p.Detail != "Only 3 available — 3 already in your cart." ||
		p.Extra["maxQuantity"] != 3 || p.Extra["available"] != 3 || p.Extra["inCart"] != 3 || p.Extra["requested"] != 4 {
		t.Fatalf("%+v", p)
	}
	p = domain.StockProblem("Garri", 0, 0, 1)
	if p.Code != domain.CodeOutOfStock || p.Extra["available"] != 0 || p.Detail != "Garri is currently unavailable." {
		t.Fatalf("%+v", p)
	}
	if _, ok := p.Extra["maxQuantity"]; ok {
		t.Fatal("OUT_OF_STOCK carries no maxQuantity")
	}
	if domain.MaxQuantity(250) != 99 || domain.MaxQuantity(-2) != 0 || domain.MaxQuantity(7) != 7 {
		t.Fatal("max quantity")
	}
	for qty, want := range map[int]string{0: "OUT_OF_STOCK", -1: "OUT_OF_STOCK", 1: "LOW_STOCK", 5: "LOW_STOCK", 6: "IN_STOCK"} {
		if got := domain.StockStatusFor(qty); got != want {
			t.Fatalf("StockStatusFor(%d) = %s", qty, got)
		}
	}
}

func TestEstimator(t *testing.T) {
	loc, _ := time.LoadLocation("America/Chicago")
	e := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900, Location: loc})
	sat := time.Date(2026, 10, 10, 12, 0, 0, 0, loc) // Saturday
	if got := e.DayLabel(1, sat); got != "Tomorrow, Oct 12" {
		t.Fatalf("Sunday is skipped: %q", got)
	}
	if got := e.DayLabel(2, sat); got != "Tuesday, Oct 13" {
		t.Fatalf("%q", got)
	}
	at := e.Promise(domain.FulfilledByAfroTransact, 0, sat)
	if at.Days != 2 || !at.Free || at.Prefix != "FREE delivery" || at.Cutoff == nil || *at.Cutoff != "Order within 11 hrs 59 mins." || at.ExpressLabel == nil {
		t.Fatalf("%+v", at)
	}
	s := e.Promise(domain.ShipsFromSeller, 3, sat)
	if s.Free || s.FeeMinor != 599 || s.Days != 3 || s.Cutoff != nil {
		t.Fatalf("%+v", s)
	}
	opts := e.Options(domain.ShipsFromSeller, 0, 5000, sat)
	if len(opts) != 1 || !opts[0].Free || opts[0].Label != "FREE · USPS Ground Advantage" {
		t.Fatalf("%+v", opts)
	}
	opts = e.Options(domain.FulfilledByAfroTransact, 0, 100, sat)
	if len(opts) != 2 || opts[1].Key != "express" || opts[1].Label != "$7.99 · UPS Next Day Air" {
		t.Fatalf("%+v", opts)
	}
	if n := e.Nudge(domain.ShipsFromSeller, 2500); n == nil || n.RemainingMinor != 2400 {
		t.Fatalf("%+v", n)
	}
	if e.Nudge(domain.FulfilledByAfroTransact, 100) != nil || e.Nudge(domain.ShipsFromSeller, 0) != nil {
		t.Fatal("no nudge for AfroTransact or empty packages")
	}
}

func TestFacetsAndHelpers(t *testing.T) {
	cases := []struct{ dim, in, value, label string }{
		{"price", "under-10", "u10", "Under $10"},
		{"price", "50-plus", "o50", "$50 & above"},
		{"rating", "4+", "4", "4 & up"},
		{"delivery", "tomorrow", "1", "Get it by tomorrow"},
		{"fulfilment", "SELLER", "SELLER", "Ships from seller"},
		{"sellerKind", "PLATFORM", "PLATFORM", "AfroTransact"},
		{"community", "Ghanaian", "Ghanaian", "Ghanaian"},
	}
	for _, c := range cases {
		v, l, ok := domain.FacetBucketFor(c.dim, c.in)
		if !ok || v != c.value || l != c.label {
			t.Fatalf("%s/%s = %s %s %v", c.dim, c.in, v, l, ok)
		}
	}
	if _, _, ok := domain.FacetBucketFor("price", "weird"); ok {
		t.Fatal("unknown buckets are dropped")
	}
	for in, want := range map[string]string{"/cart": "/cart", "//evil.com": "/", "https://evil.com": "/", "/\\evil": "/", "": "/", "/a\r\nb": "/"} {
		if got := domain.SafeReturnTo(in); got != want {
			t.Fatalf("SafeReturnTo(%q) = %q", in, got)
		}
	}
	if domain.DepartmentOf("Groceries › Grains › Rice") != "Groceries" || domain.DepartmentOf("Beauty/Hair") != "Beauty" {
		t.Fatal("department")
	}
	if domain.Money(599) != "$5.99" || domain.Money(-50) != "-$0.50" || domain.PageCount(25, 12) != 3 {
		t.Fatal("money/page count")
	}
	if domain.ProblemTypeURI("UPSTREAM_UNAVAILABLE") != "https://developers.afrotransact.com/problems/bff.upstream-unavailable" {
		t.Fatal("problem type")
	}
}
