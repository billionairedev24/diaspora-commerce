package domain

import (
	"fmt"
	"testing"
)

// catalogOf builds signals for a catalogue of n eligible products spread over four departments, with three
// communities (Nigerian biggest). It is the fixture of the 0 / 10 / 100 / 10,000 product pages.
func catalogOf(n int) HomeSignals {
	sig := HomeSignals{EligibleProducts: n, PlusEnabled: true, HeroSlides: 1}
	if n == 0 {
		return sig
	}
	depts := []string{"Groceries & Food", "Beauty & Personal Care", "Fashion & Accessories", "Home & Household"}
	shares := []int{50, 25, 15, 10}
	for i, name := range depts {
		p := n * shares[i] / 100
		sig.Departments = append(sig.Departments, UnitSignal{Name: name, Products: p, Sellers: 1 + p/10})
	}
	sig.GroceryProducts = sig.Departments[0].Products
	sig.Sellers = 1 + n/10
	sig.DealProducts, sig.NewProducts, sig.TopSellerProducts = n/10, n/2, n/3
	sig.NewestAgeDays = 2
	for i, name := range []string{"Nigerian", "Ghanaian", "Ethiopian"} {
		p := n / (3 + 4*i)
		sig.Communities = append(sig.Communities, UnitSignal{Name: name, Products: p, Sellers: 1 + p/8})
	}
	return sig
}

func shownKeys(ds []ModuleDecision) map[string]bool {
	m := map[string]bool{}
	for _, d := range ds {
		if d.Shown {
			m[d.Module] = true
		}
	}
	return m
}

func TestHomeEligibilityByCatalogueSize(t *testing.T) {
	all := []string{ModHero, ModPlus, ModDepartments, ModGroceries, ModDeals, ModNewArrivals, ModTopSellers, ModCommunities}
	cases := []struct {
		products int
		want     []string
		hidden   []string
	}{
		{0, []string{ModHero, ModPlus}, []string{ModDeals, ModDepartments, ModGroceries, ModCommunities, ModNewArrivals, ModTopSellers}},
		{10, []string{ModHero, ModPlus, ModDepartments}, []string{ModDeals, ModGroceries, ModCommunities, ModNewArrivals, ModTopSellers}},
		{100, all, nil},
		{10000, all, nil},
	}
	eng := NewHomeEligibility()
	for _, c := range cases {
		t.Run(fmt.Sprintf("%d products", c.products), func(t *testing.T) {
			got := shownKeys(eng.Evaluate(catalogOf(c.products), DefaultHomeRules()))
			for _, k := range c.want {
				if !got[k] {
					t.Errorf("%s should be shown", k)
				}
			}
			for _, k := range c.hidden {
				if got[k] {
					t.Errorf("%s should be hidden", k)
				}
			}
		})
	}
}

func TestEveryHiddenModuleSaysWhyInStaffWords(t *testing.T) {
	for _, d := range NewHomeEligibility().Evaluate(catalogOf(10), DefaultHomeRules()) {
		if !d.Shown && (d.Reason == "" || d.Detail == "" || d.Label == "") {
			t.Errorf("%q hidden without a reason: %+v", d.Module, d)
		}
	}
}

func TestCommunitiesAppearOnlyAboveTheThresholdAndNeverEmpty(t *testing.T) {
	sig := catalogOf(100)
	sig.Communities = []UnitSignal{{"Nigerian", 40, 5}, {"Ghanaian", 12, 3}, {"Ethiopian", 2, 1}, {"Jamaican", 30, 1}, {"", 50, 9}}
	rules := DefaultHomeRules()
	rules[ModCommunities] = ModuleRule{Enabled: true, MinUnits: 2, UnitMinProduct: 8, UnitMinSellers: 2, Weight: 40}
	for _, d := range NewHomeEligibility().Evaluate(sig, rules) {
		if d.Module != ModCommunities {
			continue
		}
		if !d.Shown || len(d.Units) != 2 || d.Units[0] != "Nigerian" || d.Units[1] != "Ghanaian" {
			t.Fatalf("want Nigerian then Ghanaian only, got %+v", d)
		}
	}
	sig.Communities = []UnitSignal{{"Nigerian", 40, 5}}
	for _, d := range NewHomeEligibility().Evaluate(sig, rules) {
		if d.Module == ModCommunities && d.Shown {
			t.Fatal("one community is below the module minimum: hidden")
		}
	}
}

func TestStaleCatalogueHidesNewArrivalsAndStaffCanSwitchOff(t *testing.T) {
	sig := catalogOf(100)
	sig.NewestAgeDays = 90
	rules := DefaultHomeRules()
	rules[ModTopSellers] = ModuleRule{Enabled: false}
	got := shownKeys(NewHomeEligibility().Evaluate(sig, rules))
	if got[ModNewArrivals] || got[ModTopSellers] {
		t.Errorf("stale and switched-off modules must be hidden: %v", got)
	}
}

func TestPersonalModulesNeedASignalAndShownOrderFollowsWeight(t *testing.T) {
	sig := catalogOf(100)
	ds := NewHomeEligibility().Evaluate(sig, DefaultHomeRules())
	if shownKeys(ds)[ModRecent] || shownKeys(ds)[ModContinue] {
		t.Error("a guest with no history sees neither personal module")
	}
	sig.SignedIn, sig.OrdersBought, sig.RecentlyViewed = true, 3, 2
	ds = NewHomeEligibility().Evaluate(sig, DefaultHomeRules())
	if ds[0].Module != ModHero || ds[1].Module != ModContinue || ds[2].Module != ModRecent {
		t.Errorf("order: %s %s %s", ds[0].Module, ds[1].Module, ds[2].Module)
	}
}
