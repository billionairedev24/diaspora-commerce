package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// shortRailSearch answers the rail's own query with 3 products and the relaxed department query with `more`.
func shortRailSearch(more int) port.SearchClient {
	return fnSearch{fn: func(p port.SearchParams) (port.SearchResult, error) {
		if p.Department != "" {
			return port.SearchResult{Hits: manyHits("ATREL", more)}, nil
		}
		return port.SearchResult{Hits: manyHits("ATORG", 3)}, nil
	}}
}

func shortRail() domain.Placements {
	return domain.Placements{Rails: []domain.RailPlacement{{ID: "few", Title: "Few", Href: "/s", Source: domain.RailSource{Kind: "search"}, MinItems: 2}}}
}

func fillPlacement(id, adv, extra string) domain.BodyPlacement {
	p := `{"headline":"Jollof week","text":"Rice and spice","cta":"Shop","href":"/s?q=jollof","imageUrl":"https://media.example/a.webp"` + extra
	if adv != "" {
		p += `,"advertiser":"` + adv + `"`
	}
	return domain.BodyPlacement{ID: id, Slot: "rail_fill", Priority: 1, Advertiser: adv, Payload: payload(p + `}`)}
}

func TestShortRailIsFilledWithRelevantProductsFirst(t *testing.T) {
	pl := shortRail()
	pl.RailFill = []domain.BodyPlacement{fillPlacement("plc_ad", "Acme", "")}
	h, _ := composeService(pl, shortRailSearch(10), nil).Home(context.Background(), nil)
	b := find(h, "few")
	if b == nil || len(b.Rail.Items) != domain.RailFillTarget || b.Rail.Fill != nil {
		t.Fatalf("relaxed products should fill the row before any ad: n=%d fill=%+v", len(b.Rail.Items), b.Rail.Fill)
	}
	seen := map[string]bool{}
	for _, p := range b.Rail.Items {
		if seen[p.ATIN] || !eligibleCard(p) {
			t.Fatalf("duplicate or ineligible product %s", p.ATIN)
		}
		seen[p.ATIN] = true
	}
}

func TestShortRailGetsSponsoredThenEditorialTileAndNothingElseCollapses(t *testing.T) {
	ctx := context.Background()
	pl := shortRail()
	pl.RailFill = []domain.BodyPlacement{fillPlacement("plc_ed", "", ""), fillPlacement("plc_ad", "Acme", "")}
	h, _ := composeService(pl, shortRailSearch(0), nil).Home(ctx, nil)
	f := find(h, "few").Rail.Fill
	if f == nil || !f.Sponsored || f.Advertiser != "Acme" || f.ID != "plc_ad" {
		t.Fatalf("paid tile first, labelled with its advertiser: %+v", f)
	}
	pl.RailFill = []domain.BodyPlacement{fillPlacement("plc_ed", "", "")}
	h, _ = composeService(pl, shortRailSearch(0), nil).Home(ctx, nil)
	if f := find(h, "few").Rail.Fill; f == nil || f.Sponsored || f.ID != "plc_ed" {
		t.Fatalf("editorial tile is not Sponsored: %+v", f)
	}
	pl.RailFill = []domain.BodyPlacement{{ID: "plc_bad", Slot: "rail_fill", Payload: payload(`{"headline":"No link"}`)}}
	h, _ = composeService(pl, shortRailSearch(0), nil).Home(ctx, nil)
	if b := find(h, "few"); b == nil || b.Rail.Fill != nil || len(b.Rail.Items) != 3 {
		t.Fatalf("nothing valid: the rail collapses, no placeholder tile: %+v", b)
	}
}

func TestFillTileDropsNonPhotoImageAndUsesOnlyEligibleProducts(t *testing.T) {
	pl := shortRail()
	pl.RailFill = []domain.BodyPlacement{fillPlacement("plc_ed", "", "")}
	pl.RailFill[0].Payload = payload(`{"headline":"H","cta":"Go","href":"/s","imageUrl":"https://media.example/placeholder.svg"}`)
	h, _ := composeService(pl, shortRailSearch(0), nil).Home(context.Background(), nil)
	if f := find(h, "few").Rail.Fill; f == nil || f.ImageURL != nil {
		t.Fatalf("a svg or placeholder is never a tile photo: %+v", f)
	}
}

func TestRecentlyViewedIsNeverToppedUpWithOtherProductsButGetsATile(t *testing.T) {
	search := &rulesSearch{hits: map[string][]port.SearchHit{"Groceries|rating|": manyHits("ATREL", 10)}}
	pl := domain.Placements{RailFill: []domain.BodyPlacement{fillPlacement("plc_ed", "", "")}}
	svc := newHomeService(pl, search, nil).(*siteServiceImpl)
	items, fill, err := svc.RecentlyViewedRail(context.Background(), nil, []string{"ATGARI0001", "ATRICE0001"})
	if err != nil || len(items) != 2 {
		t.Fatalf("items=%v err=%v", items, err)
	}
	if fill == nil || fill.Sponsored || fill.ID != "plc_ed" {
		t.Fatalf("editorial tile expected: %+v", fill)
	}
	empty, f2, _ := svc.RecentlyViewedRail(context.Background(), nil, nil)
	if len(empty) != 0 || f2 != nil {
		t.Fatalf("nothing viewed: no rail and no tile")
	}
}

func TestOnlyRailsThatKeepTheirMeaningAreToppedUpWithProducts(t *testing.T) {
	rail := func(typ string, relaxable bool) domain.HomeBlock {
		return domain.HomeBlock{Type: typ, Rail: &domain.Rail{Relaxable: relaxable}}
	}
	cases := []struct {
		name         string
		b            domain.HomeBlock
		ok, dealOnly bool
	}{
		{"generic content rail", rail(domain.BlockRail, true), true, false},
		{"delivery-promise or near-you rail", rail(domain.BlockRail, false), false, false},
		{"deals keep to real deals", rail(domain.BlockDeals, false), true, true},
		{"based on orders", rail(domain.BlockBasedOnOrders, false), false, false},
		{"seller spotlight", rail(domain.BlockSeller, false), false, false},
		{"groceries module", domain.HomeBlock{Type: domain.BlockRail, Module: domain.ModGroceries, Rail: &domain.Rail{}}, true, false},
	}
	for _, c := range cases {
		if ok, deals := relaxMode(c.b); ok != c.ok || deals != c.dealOnly {
			t.Errorf("%s: relax=%v dealsOnly=%v", c.name, ok, deals)
		}
	}
}
