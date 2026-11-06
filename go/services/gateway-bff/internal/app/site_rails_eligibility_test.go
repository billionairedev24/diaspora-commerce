package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// TestNoRailKindReturnsAnItemWithoutARealPhoto: every rail source kind (and a brand rail, which is a plain search) goes
// through the same eligibility rule, so a product without a real photo or out of stock never reaches the home page.
func TestNoRailKindReturnsAnItemWithoutARealPhoto(t *testing.T) {
	bad := []port.SearchHit{hit("ATNOIMG001", "No photo", "Fashion", "", 5), hit("ATSVG00001", "Icon", "Fashion", "https://x/icon.svg", 5),
		hit("ATPLACE001", "Placeholder", "Fashion", "https://x/placeholder.png", 5), hit("ATOUT00001", "Sold out", "Fashion", photo("ATOUT00001"), 5)}
	bad[3].InStock = false
	good := hit("ATDRESS001", "Wax dress", "Fashion", photo("ATDRESS001"), 4)
	search := &rulesSearch{hits: map[string][]port.SearchHit{}}
	for _, k := range []string{"|relevance|", "Fashion|rating|", "Fashion|relevance|", "|rating|", "|relevance|AfroTransact", "|rating|AfroTransact"} {
		search.hits[k] = append(append([]port.SearchHit{}, bad...), good)
	}
	svc, ok := newHomeService(domain.Placements{}, search, nil).(*siteServiceImpl)
	if !ok {
		t.Fatal("not the site service")
	}
	sc := newScope(homeLocation{}.Current(context.Background(), nil), &domain.Caller{}, homeNow)
	sources := []domain.RailSource{
		{Kind: "search", Department: "Fashion", Sort: "rating"},
		{Kind: "search", Query: "AfroTransact"},
		{Kind: "brand", Query: "AfroTransact"},
		{Kind: "department-bestsellers", Department: "Fashion"},
		{Kind: "fulfilled-fastest"},
		{Kind: "near-you"},
		{Kind: "deliverable-fast", MaxDays: 5},
		{Kind: "community", Community: "nigerian"},
		{Kind: "atins", ATINs: []string{"ATOUT00001", "ATDRESS001"}},
		{Kind: "something-new"},
	}
	for _, src := range sources {
		items, err := svc.railItems(context.Background(), src, sc)
		if err != nil {
			t.Fatalf("%s: %v", src.Kind, err)
		}
		for _, p := range items {
			if !p.InStock || !realPhoto(p.ImageURL) {
				t.Errorf("%s rail returned %s without a real photo or out of stock: %+v", src.Kind, p.ATIN, p)
			}
		}
	}
}
