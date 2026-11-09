package app

import (
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func fullAccess() sellerCtx {
	return sellerCtx{access: domain.Access{Kind: domain.OrgKindProvider, Role: "provider.owner", Matrix: domain.DefaultProviderMatrix}}
}

func TestEmptyModulesCarryAPromptAndNoFigures(t *testing.T) {
	sc := fullAccess()
	for _, m := range []domain.ModuleSummary{
		payoutsModule(sc, &port.PayoutBalanceResponse{}),
		reviewsModule(sc, &reviewSample{}),
		inventoryModule(sc, nil),
		promotionsModule(sc, nil),
		providerRevenue(sc, &port.BookingsAnalytics{}, 30),
	} {
		if m.State != domain.ModuleEmpty || m.Headline != nil || m.Chart != nil || m.Empty == nil || m.Empty.Action.Href == "" || len(m.Actions) != 0 {
			t.Fatalf("%s should be an empty state with one action and no figures: %+v", m.ID, m)
		}
	}
}

func TestReviewsModuleShowsAnAverageOnlyWithReviews(t *testing.T) {
	m := reviewsModule(fullAccess(), &reviewSample{total: 3, unanswered: 1, items: []port.UpSellerReview{{Rating: 5}, {Rating: 4}, {Rating: 3}}})
	if m.State != domain.ModuleReady || m.Headline.Value != "4.0" || m.TotalLine == nil || !strings.Contains(*m.TotalLine, "1 waiting for a reply") {
		t.Fatalf("reviews = %+v", m)
	}
	if len(m.Chart.Points) != 5 || m.Chart.Points[0].Label != "5 stars" || m.Chart.Points[4].Label != "1 star" {
		t.Fatalf("star bars = %+v", m.Chart.Points)
	}
}

func TestPayoutsModuleNamesTheNextRelease(t *testing.T) {
	b := &port.PayoutBalanceResponse{AvailableMinor: 12000, OnHoldMinor: 5000}
	b.OnHold = append(b.OnHold, struct {
		OrderID     string `json:"orderId"`
		AmountMinor int64  `json:"amountMinor"`
		ReleaseAt   string `json:"releaseAt"`
	}{"o2", 2000, "2026-11-14T00:00:00Z"}, struct {
		OrderID     string `json:"orderId"`
		AmountMinor int64  `json:"amountMinor"`
		ReleaseAt   string `json:"releaseAt"`
	}{"o1", 3000, "2026-11-12T00:00:00Z"})
	m := payoutsModule(fullAccess(), b)
	if m.Headline.Value != "$120.00" || *m.TotalLine != "$50.00 still settling · next release Nov 12" {
		t.Fatalf("payouts = %+v / %s", m.Headline, *m.TotalLine)
	}
}

func TestProviderCalendarCountsConfirmedBookingsOnly(t *testing.T) {
	loc := time.UTC
	now := time.Date(2026, 11, 9, 10, 0, 0, 0, loc)
	cal := &port.BookingsCalendar{OpenHours: []domain.DayHours{{Day: "MON", Ranges: []domain.TimeRange{{Start: "09:00", End: "17:00"}}}}, Bookings: []domain.BookingCard{
		{Status: "CONFIRMED", StartAt: now.Add(26 * time.Hour)},
		{Status: "CONFIRMED", StartAt: now.Add(50 * time.Hour)},
		{Status: "CANCELLED_CUSTOMER", StartAt: now.Add(26 * time.Hour)},
		{Status: "CONFIRMED", StartAt: now.Add(20 * 24 * time.Hour)},
	}}
	m := providerCalendar(fullAccess(), cal, now, loc)
	if m.Headline.Value != "2" || *m.TotalLine != "3 confirmed bookings this month" || len(m.Chart.Points) != 7 {
		t.Fatalf("calendar = %+v / %s", m.Headline, *m.TotalLine)
	}
	cal.OpenHours = nil
	if e := providerCalendar(fullAccess(), cal, now, loc); e.State != domain.ModuleEmpty || e.Empty.Action.Href != "/hours" {
		t.Fatalf("no hours set should prompt for them: %+v", e)
	}
}

func TestQuickActionsAreLimitedToWhatTheMemberMayOpen(t *testing.T) {
	// seller.fulfilment: orders yes, listings view-only (no Add product), payouts no.
	sc := sellerCtx{access: domain.Access{Kind: domain.OrgKindSeller, Role: "seller.fulfilment", Matrix: domain.DefaultSellerMatrix}}
	got := sc.links([3]string{"orders", "All orders", "/orders"}, [3]string{"+listings", "Add product", "/listings/new"}, [3]string{"payouts", "Payouts", "/payouts"})
	if len(got) != 1 || got[0].Href != "/orders" {
		t.Fatalf("links = %+v", got)
	}
}
