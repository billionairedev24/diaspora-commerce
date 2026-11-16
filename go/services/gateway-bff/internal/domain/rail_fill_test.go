package domain

import "testing"

func fp(atin, dept string) ProductSummary {
	img := "https://media.example/" + atin + ".webp"
	return ProductSummary{ATIN: atin, Department: dept, InStock: true, ImageURL: &img}
}

func rf(n int, prefix string) []ProductSummary {
	out := make([]ProductSummary, n)
	for i := range out {
		out[i] = fp(prefix+string(rune('A'+i)), "Groceries")
	}
	return out
}

func paid(id, adv string) RailFillCandidate {
	return RailFillCandidate{Tile: RailFill{ID: id, Advertiser: adv, Title: "Promo " + id, Href: "/s?q=x", CTA: "Shop"}}
}

func TestFullRailGetsNoFill(t *testing.T) {
	r := PickRailFill(RailFillInput{Items: rf(7, "A"), Relaxed: rf(3, "R"), Placements: []RailFillCandidate{paid("p1", "Acme")}})
	if r.Tier != "none" || r.Fill != nil || len(r.Items) != 7 {
		t.Fatalf("got %+v", r)
	}
}

func TestMoreProductsFirstAndDedupe(t *testing.T) {
	items := rf(3, "A")
	relaxed := append([]ProductSummary{items[0]}, rf(6, "R")...) // first repeats a rail item
	used := map[string]bool{"RA": true}                          // and one is on the page already
	r := PickRailFill(RailFillInput{Items: items, Relaxed: relaxed, Used: used, Placements: []RailFillCandidate{paid("p1", "Acme")}})
	if r.Tier != "products" || r.Fill != nil || len(r.Items) != RailFillTarget {
		t.Fatalf("got tier %s fill %v n=%d", r.Tier, r.Fill, len(r.Items))
	}
	seen := map[string]bool{}
	for _, p := range r.Items {
		if seen[p.ATIN] || p.ATIN == "RA" && p.ATIN != items[0].ATIN {
			t.Fatalf("duplicate or used product %s", p.ATIN)
		}
		seen[p.ATIN] = true
	}
}

func TestSponsoredBeforeEditorialAndLabelled(t *testing.T) {
	editorial := RailFillCandidate{Tile: RailFill{ID: "e1", Title: "Back to school", Href: "/s?q=school"}, Priority: 99}
	r := PickRailFill(RailFillInput{Items: rf(3, "A"), Placements: []RailFillCandidate{editorial, paid("p1", "Acme")}})
	if r.Tier != "sponsored" || r.Fill == nil || !r.Fill.Sponsored || r.Fill.Advertiser != "Acme" || r.Fill.ID != "p1" {
		t.Fatalf("got %+v", r.Fill)
	}
}

func TestAuctionBeatsEditorialButNeedsAdvertiserAndProducts(t *testing.T) {
	editorial := RailFillCandidate{Tile: RailFill{ID: "e1", Title: "Easter", Href: "/s?q=easter"}}
	good := func() *RailFill {
		return &RailFill{Kind: RailFillSponsoredProducts, Sponsored: true, Advertiser: "Marketplace sellers", Title: "Sponsored", Href: "/s", Products: []ProductSummary{fp("S1", "Groceries"), fp("A0", "Groceries")}}
	}
	items := rf(3, "A")
	items[0] = fp("A0", "Groceries")
	r := PickRailFill(RailFillInput{Items: items, Auction: good, Placements: []RailFillCandidate{editorial}})
	if r.Tier != "sponsored" || len(r.Fill.Products) != 1 || r.Fill.Products[0].ATIN != "S1" {
		t.Fatalf("auction tile should drop products already in the rail: %+v", r.Fill)
	}
	unlabelled := func() *RailFill { return &RailFill{Sponsored: true, Title: "x", Products: rf(1, "S")} }
	r = PickRailFill(RailFillInput{Items: rf(3, "A"), Auction: unlabelled, Placements: []RailFillCandidate{editorial}})
	if r.Tier != "placement" {
		t.Fatalf("an unlabelled ad must never fill, got %s", r.Tier)
	}
}

func TestEditorialIsNotSponsored(t *testing.T) {
	r := PickRailFill(RailFillInput{Items: rf(3, "A"), Placements: []RailFillCandidate{{Tile: RailFill{ID: "e1", Title: "Detty December", Href: "/s?q=party"}}}})
	if r.Tier != "placement" || r.Fill.Sponsored {
		t.Fatalf("got %+v", r)
	}
}

func TestDepartmentTargetingAndPriority(t *testing.T) {
	a := paid("a", "Acme")
	a.Departments = []string{"Fashion"}
	b := paid("b", "Bolt")
	b.Priority = 5
	c := paid("c", "Cola")
	r := PickRailFill(RailFillInput{Items: rf(3, "A"), Department: "groceries", Placements: []RailFillCandidate{a, c, b}})
	if r.Fill == nil || r.Fill.ID != "b" {
		t.Fatalf("want highest priority targeting groceries, got %+v", r.Fill)
	}
	r = PickRailFill(RailFillInput{Items: rf(3, "A"), Department: "Fashion", Placements: []RailFillCandidate{a}})
	if r.Fill == nil || r.Fill.ID != "a" {
		t.Fatalf("fashion rail should get the fashion tile, got %+v", r.Fill)
	}
}

func TestNothingToShowCollapsesWithoutPlaceholder(t *testing.T) {
	r := PickRailFill(RailFillInput{Items: rf(3, "A"), Placements: []RailFillCandidate{{Tile: RailFill{ID: "x"}}}})
	if r.Tier != "collapse" || r.Fill != nil || len(r.Items) != 3 {
		t.Fatalf("got %+v", r)
	}
}

func TestRotateAmongEqualPriority(t *testing.T) {
	in := RailFillInput{Items: rf(3, "A"), Placements: []RailFillCandidate{paid("a", "A"), paid("b", "B")}}
	in.Rotate = 1
	if r := PickRailFill(in); r.Fill.ID != "b" {
		t.Fatalf("got %s", r.Fill.ID)
	}
	in.Rotate = 0
	if r := PickRailFill(in); r.Fill.ID != "a" {
		t.Fatalf("got %s", r.Fill.ID)
	}
}
