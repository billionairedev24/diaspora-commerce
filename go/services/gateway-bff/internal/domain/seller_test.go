package domain_test

import (
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func keys(groups []domain.NavGroup) []string {
	var out []string
	for _, g := range groups {
		for _, m := range g.Modules {
			out = append(out, m.Key)
		}
	}
	return out
}

func TestNavigationFollowsTheSeededMatrix(t *testing.T) {
	owner := domain.Access{Kind: domain.OrgKindSeller, Role: "seller.owner", Matrix: domain.DefaultSellerMatrix}
	if got := keys(domain.Navigation(owner, true, nil)); len(got) != 9 || got[0] != "home" {
		t.Fatalf("owner nav = %v", got)
	}
	support := domain.Access{Kind: domain.OrgKindSeller, Role: "seller.support", Matrix: domain.DefaultSellerMatrix}
	if got := keys(domain.Navigation(support, true, nil)); !slices.Equal(got, []string{"home", "orders", "listings"}) {
		t.Fatalf("support nav = %v", got)
	}
	practitioner := domain.Access{Kind: domain.OrgKindProvider, Role: "provider.practitioner", Matrix: domain.DefaultProviderMatrix}
	got := keys(domain.Navigation(practitioner, true, map[string]int{"requests": 2}))
	if slices.Contains(got, "public-page") || slices.Contains(got, "payouts") || !slices.Contains(got, "hours") {
		t.Fatalf("practitioner nav = %v", got)
	}
	if lv := practitioner.Level("policy"); lv != domain.LevelView {
		t.Fatalf("practitioner policy level = %s", lv)
	}
	if got := keys(domain.Navigation(owner, false, nil)); !slices.Equal(got, []string{"onboarding"}) {
		t.Fatalf("unapproved nav = %v", got)
	}
}

func TestRoleCardsListChangeViewAndNoAccess(t *testing.T) {
	cards := domain.RoleCards(domain.OrgKindProvider, domain.DefaultProviderMatrix)
	if len(cards) != 4 {
		t.Fatalf("cards = %d", len(cards))
	}
	fin := cards[3]
	if fin.Key != "provider.finance" || fin.Note == nil || !slices.Contains(fin.Can, "payouts and balances") ||
		!slices.Contains(fin.View, "services, prices and deposits") || !slices.Contains(fin.Cannot, "the public page design, gallery and social links") {
		t.Fatalf("finance card = %+v", fin)
	}
}

func TestComplianceBannerCopy(t *testing.T) {
	d := time.Date(2026, 10, 24, 0, 0, 0, 0, time.UTC)
	s := domain.ComplianceSummary{Status: domain.ComplianceRestricted, OpenItems: 2, Deadline: &d}
	domain.ComplianceBanner(&s, time.UTC)
	if !s.ShowBanner || s.Tone != "danger" || s.Title != "Payouts paused — verification needed." || s.Message != "2 item(s) due Oct 24. Open Account health to resolve." {
		t.Fatalf("restricted = %+v", s)
	}
	ok := domain.ComplianceSummary{Status: domain.ComplianceCompliant}
	domain.ComplianceBanner(&ok, nil)
	if ok.ShowBanner {
		t.Fatal("compliant accounts show no banner")
	}
}

func TestPolicyDescribeAndValidate(t *testing.T) {
	m := domain.PolicyInput{Kind: "MODERATE"}.Describe()
	if m.FreeHours != 48 || len(m.Timeline) != 3 || m.Timeline[1].RefundPercent != 50 || !strings.HasPrefix(m.Summary, "Free cancellation up to 2 days before.") {
		t.Fatalf("moderate = %+v", m)
	}
	bad := domain.PolicyInput{Kind: "CUSTOM", FreeHours: 24, PartialRefund: true, PartialFromHours: 30, PartialPercent: 50, NoShow: "DEPOSIT"}
	if errs := bad.Validate(); errs["partialFromHours"] == "" {
		t.Fatalf("partial window above the free window must fail: %v", errs)
	}
}

func TestEarningsTotalsSubtractCommissionFeesAndLateCancel(t *testing.T) {
	e := domain.Earnings{Totals: domain.EarningsTotals{HeldMinor: 4000}, Rows: []domain.EarningsRow{
		{Status: "COMPLETED", CollectedMinor: 16000, CommissionMinor: 1600, CardFeesMinor: 494},
		{Status: "CANCELLED_PROVIDER", LateCancelFeeMinor: 1500},
	}}
	e.Total()
	if e.Totals.NetMinor != 16000-1600-494-1500 || e.Totals.LateCancelFeesMinor != 1500 || e.Totals.HeldMinor != 4000 || e.Rows[1].StatusLabel != "Cancelled by provider" {
		t.Fatalf("totals = %+v rows = %+v", e.Totals, e.Rows)
	}
}

func TestDraftAndAvailabilityValidation(t *testing.T) {
	d := domain.ProductDraft{CategoryID: "c", CategoryRequestID: "r", Title: "Too short", VariationTheme: "Size",
		Variants: []domain.VariantInput{{SellerSKU: "A", PriceMinor: 100}, {SellerSKU: "A", PriceMinor: 100, GTINExempt: true}}}
	errs := d.Validate()
	for _, k := range []string{"categoryId", "title", "images", "variants"} {
		if errs[k] == "" {
			t.Fatalf("draft errors = %v (want %s)", errs, k)
		}
	}
	a := domain.Availability{Weekly: []domain.DayHours{{Day: "MON", Ranges: []domain.TimeRange{{Start: "09:00", End: "12:00"}, {Start: "11:00", End: "13:00"}}}},
		Rules: domain.BookingRules{HorizonDays: 30, StepMinutes: 30, MaxPerDay: 6}}
	if errs := a.Validate(); errs["MON"] == "" || errs["weekly"] == "" {
		t.Fatalf("availability errors = %v", errs)
	}
	if !domain.SocialURLMatches("instagram", "https://www.instagram.com/x") || domain.SocialURLMatches("tiktok", "https://instagram.com/x") {
		t.Fatal("social link matching")
	}
	if !domain.CanBuyLabel("SELLER", "CARRIER", "PACKED", "") || domain.CanBuyLabel("SELLER", "PICKUP", "PACKED", "") || domain.CanBuyLabel("AFROTRANSACT", "CARRIER", "PACKED", "") {
		t.Fatal("label rules")
	}
}
