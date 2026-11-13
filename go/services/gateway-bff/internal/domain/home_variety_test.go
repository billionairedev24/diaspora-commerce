package domain

import (
	"fmt"
	"reflect"
	"testing"
)

func pool(n, sellers int) []VarietyItem {
	out := make([]VarietyItem, n)
	for i := range out {
		out[i] = VarietyItem{Key: fmt.Sprintf("AT%04d", i), Seller: fmt.Sprintf("s%d", i%sellers), Quality: 0.5 + float64(i%5)/10}
	}
	return out
}

func keysOf(items []VarietyItem) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.Key
	}
	return out
}

func TestVarietyIsStableWithinAVisit(t *testing.T) {
	p := VarietyParams{Seed: SeedOf("visitor-1", "window-1"), Module: "deals", Show: 8, SellerShare: 2, NoveltyPenalty: 0.6}
	first := keysOf(PickVariety(pool(24, 8), p))
	for i := 0; i < 20; i++ {
		if got := keysOf(PickVariety(pool(24, 8), p)); !reflect.DeepEqual(got, first) {
			t.Fatalf("same visit must give the same picks: %v vs %v", got, first)
		}
	}
}

func TestVarietyDiffersAcrossVisits(t *testing.T) {
	base := VarietyParams{Module: "deals", Show: 8, SellerShare: 2, NoveltyPenalty: 0.6}
	seen := map[string]bool{}
	for v := 0; v < 30; v++ {
		p := base
		p.Seed = SeedOf("visitor-1", fmt.Sprintf("window-%d", v))
		seen[fmt.Sprint(keysOf(PickVariety(pool(24, 8), p)))] = true
	}
	if len(seen) < 25 {
		t.Fatalf("30 visits produced only %d different product sets", len(seen))
	}
	// a pool 3x the module means two visits share only part of their items
	a := keysOf(PickVariety(pool(24, 8), VarietyParams{Seed: SeedOf("v", "1"), Module: "m", Show: 8, SellerShare: 2}))
	b := keysOf(PickVariety(pool(24, 8), VarietyParams{Seed: SeedOf("v", "2"), Module: "m", Show: 8, SellerShare: 2}))
	same := 0
	for _, x := range a {
		for _, y := range b {
			if x == y {
				same++
			}
		}
	}
	if same == len(a) {
		t.Fatal("two visits showed the identical products")
	}
}

func TestNoveltyMemoryDownWeightsAndExclusionRemoves(t *testing.T) {
	pl := pool(24, 24)
	shown := 0
	rem := map[string]bool{}
	var firstSet []string
	for v := 0; v < 200; v++ {
		p := VarietyParams{Seed: SeedOf("v", fmt.Sprint(v)), Module: "m", Show: 8, NoveltyPenalty: 0.6}
		if v == 0 {
			firstSet = keysOf(PickVariety(pl, p))
		}
	}
	for _, k := range firstSet {
		rem[ShortKey(k)] = true
	}
	withMem, without := 0, 0
	for v := 0; v < 300; v++ {
		p := VarietyParams{Seed: SeedOf("n", fmt.Sprint(v)), Module: "m", Show: 8, NoveltyPenalty: 0.6}
		for _, k := range keysOf(PickVariety(pl, p)) {
			for _, f := range firstSet {
				if k == f {
					without++
				}
			}
		}
		p.Novelty = rem
		for _, k := range keysOf(PickVariety(pl, p)) {
			for _, f := range firstSet {
				if k == f {
					withMem++
				}
			}
		}
	}
	shown = withMem
	if !(withMem < without*8/10) {
		t.Fatalf("novelty should cut repeats clearly: %d with memory, %d without", shown, without)
	}
	ex := map[string]bool{"AT0001": true, "AT0002": true}
	for v := 0; v < 50; v++ {
		for _, k := range keysOf(PickVariety(pl, VarietyParams{Seed: SeedOf("x", fmt.Sprint(v)), Module: "m", Show: 20, Exclude: ex})) {
			if ex[k] {
				t.Fatalf("%s is in recently viewed / cart and must not be in a discovery module", k)
			}
		}
	}
}

func TestSellerShareCapAndRelaxationWhenFewSellers(t *testing.T) {
	got := PickVariety(pool(24, 6), VarietyParams{Seed: 7, Module: "m", Show: 8, SellerShare: 2})
	per := map[string]int{}
	for _, it := range got {
		per[it.Seller]++
		if per[it.Seller] > 2 {
			t.Fatalf("seller %s has %d items, cap is 2", it.Seller, per[it.Seller])
		}
	}
	if len(got) != 8 {
		t.Fatalf("6 sellers x 2 fills 8: got %d", len(got))
	}
	one := PickVariety(pool(24, 1), VarietyParams{Seed: 7, Module: "m", Show: 8, SellerShare: 2})
	if len(one) != 8 {
		t.Fatalf("one seller marketplace must still fill the module (share relaxes only then): %d", len(one))
	}
}

func TestNoDuplicateKeys(t *testing.T) {
	pl := append(pool(10, 3), pool(10, 3)...)
	got := keysOf(PickVariety(pl, VarietyParams{Seed: 1, Module: "m", Show: 20}))
	seen := map[string]bool{}
	for _, k := range got {
		if seen[k] {
			t.Fatalf("duplicate %s", k)
		}
		seen[k] = true
	}
}

func TestDeterministicIgnoresSeedAndNovelty(t *testing.T) {
	a := keysOf(PickVariety(pool(24, 8), VarietyParams{Seed: 1, Module: "m", Show: 8, SellerShare: 2, Deterministic: true}))
	b := keysOf(PickVariety(pool(24, 8), VarietyParams{Seed: 99, Module: "m", Show: 8, SellerShare: 2, Deterministic: true,
		Novelty: map[string]bool{ShortKey(a[0]): true}}))
	if !reflect.DeepEqual(a, b) {
		t.Fatalf("crawler order must not depend on seed or memory: %v %v", a, b)
	}
	if a[0] != "AT0004" && QualityByKey(pool(24, 8), a[0]) < 0.89 {
		t.Fatalf("deterministic order is best quality first, got %v", a)
	}
}

func QualityByKey(p []VarietyItem, k string) float64 {
	for _, it := range p {
		if it.Key == k {
			return it.Quality
		}
	}
	return 0
}

func TestQualityWeightsFavourBetterItems(t *testing.T) {
	pl := []VarietyItem{{Key: "good", Seller: "a", Quality: 1}, {Key: "meh", Seller: "b", Quality: 0.1}}
	good := 0
	for v := 0; v < 500; v++ {
		if PickVariety(pl, VarietyParams{Seed: uint64(v + 1), Module: "m", Show: 1})[0].Key == "good" {
			good++
		}
	}
	if good < 400 {
		t.Fatalf("a much better item should win most visits: %d/500", good)
	}
}

func TestQualityNeverFavoursUnreviewed(t *testing.T) {
	none := QualityOf(ProductSummary{AvailableQty: 30}, 0)
	bad := QualityOf(ProductSummary{AvailableQty: 30, Rating: 1.5, ReviewCount: 50}, 0)
	great := QualityOf(ProductSummary{AvailableQty: 30, Rating: 4.8, ReviewCount: 50}, 0)
	if !(great > none && none > bad-0.2) {
		t.Fatalf("great %.2f none %.2f bad %.2f", great, none, bad)
	}
}

func TestModuleOrderVariesWithinBandsAndHeroStaysFirst(t *testing.T) {
	mods := []OrderedModule{
		{Key: "deals", Band: BandDiscovery, Weight: 85}, {Key: "new", Band: BandDiscovery, Weight: 60},
		{Key: "top", Band: BandDiscovery, Weight: 55}, {Key: "comm", Band: BandDiscovery, Weight: 40},
		{Key: "hero", Band: BandHero, Weight: 100}, {Key: "cont", Band: BandPersonal, Weight: 95},
		{Key: "sell", Band: BandBrand, Weight: 5}, {Key: "plus", Band: BandPromo, Weight: 10},
	}
	orders := map[string]bool{}
	for v := 0; v < 40; v++ {
		o := OrderModules(mods, SeedOf("v", fmt.Sprint(v)), false)
		if o[0].Key != "hero" || o[1].Key != "cont" || o[len(o)-1].Key != "sell" || o[len(o)-2].Key != "plus" {
			t.Fatalf("bands must hold: %v", o)
		}
		orders[fmt.Sprint(o)] = true
		if again := OrderModules(mods, SeedOf("v", fmt.Sprint(v)), false); !reflect.DeepEqual(again, o) {
			t.Fatal("order must be stable for one seed")
		}
	}
	if len(orders) < 5 {
		t.Fatalf("discovery order should rotate across visits, saw %d orders", len(orders))
	}
	d1 := OrderModules(mods, 1, true)
	d2 := OrderModules(mods, 2, true)
	if !reflect.DeepEqual(d1, d2) {
		t.Fatal("crawler order is the default order")
	}
	if d1[2].Key != "deals" || d1[3].Key != "new" {
		t.Fatalf("default order is by weight: %v", d1)
	}
}

func TestPinnedPlacementsWinTheirBand(t *testing.T) {
	mods := []OrderedModule{{Key: "auto", Band: BandDiscovery, Weight: 99}, {Key: "pin", Band: BandDiscovery, Weight: 1, Pinned: true}}
	for v := 0; v < 20; v++ {
		if OrderModules(mods, uint64(v+1), false)[0].Key != "pin" {
			t.Fatal("staff pins win ties over automatic modules")
		}
	}
}

func TestSponsoredCapOnePerThreeOrganic(t *testing.T) {
	flags := []bool{false, true, false, true, false, false, true, false}
	keep := CapSponsored(flags, 3) // 5 organic -> 1 sponsored
	n := 0
	for i, f := range flags {
		if f && keep[i] {
			n++
		}
		if !f && !keep[i] {
			t.Fatal("organic modules are never dropped")
		}
	}
	if n != 1 || !keep[1] {
		t.Fatalf("want the earliest single sponsored kept: %v", keep)
	}
	if k := CapSponsored([]bool{true, true}, 3); k[0] || k[1] {
		t.Fatal("paid modules never fill a page with no organic module")
	}
}

func TestSeedLabelRoundTrip(t *testing.T) {
	s := SeedOf("a", "b")
	got, ok := ParseSeedLabel(SeedLabel(s))
	if !ok || got != s {
		t.Fatal("round trip")
	}
	if _, ok := ParseSeedLabel("zz"); ok {
		t.Fatal("garbage rejected")
	}
}

// ---- eligibility additions (S416, S421, S422)

func decisionOf(ds []ModuleDecision, key string) ModuleDecision {
	for _, d := range ds {
		if d.Module == key {
			return d
		}
	}
	return ModuleDecision{}
}

func TestHysteresisKeepsAShownModuleUntilTwoBelowItsMinimum(t *testing.T) {
	eng := NewHomeEligibility()
	rules := DefaultHomeRules() // groceries needs 6
	sig := catalogOf(100)
	for have, wantShown := range map[int]bool{6: true, 5: true, 4: true, 3: false} {
		sig.GroceryProducts = have
		prev := map[string]bool{ModGroceries: true}
		got := decisionOf(eng.EvaluateWith(sig, rules, prev, 2), ModGroceries)
		if got.Shown != wantShown {
			t.Errorf("shown before, %d products: shown=%v want %v", have, got.Shown, wantShown)
		}
	}
	sig.GroceryProducts = 5
	if decisionOf(eng.EvaluateWith(sig, rules, nil, 2), ModGroceries).Shown {
		t.Error("a module that was hidden needs its full minimum to appear")
	}
}

func TestServicesShowWhenAnyProviderExistsAndBrandHeroOnlyWithoutACampaign(t *testing.T) {
	eng := NewHomeEligibility()
	sig := HomeSignals{ServiceProvidersAll: 3, HeroSlides: 0}
	ds := eng.Evaluate(sig, DefaultHomeRules())
	if !decisionOf(ds, ModServices).Shown || !decisionOf(ds, ModBrandHero).Shown || !decisionOf(ds, ModSellBand).Shown {
		t.Errorf("0 products with providers: services, brand hero and sell band: %+v", shownKeys(ds))
	}
	sig.HeroSlides = 2
	if decisionOf(eng.Evaluate(sig, DefaultHomeRules()), ModBrandHero).Shown {
		t.Error("a scheduled campaign replaces the plain brand hero")
	}
	if decisionOf(eng.Evaluate(HomeSignals{}, DefaultHomeRules()), ModServices).Shown {
		t.Error("no provider anywhere: no Services module")
	}
}
