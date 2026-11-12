package domain

import (
	"fmt"
	"sort"
)

// Dynamic home spike (docs/engineering/dynamic-home-spike.md): a pure module-eligibility function. It is NOT wired into
// SiteService.Home; it proves the rule shape staff will edit and the explanation staff will read. Every count in
// HomeSignals is already restricted to what shoppers may see: live with a stored RESOLVED tax decision, in stock, a real
// photograph (eligibleCard), never sponsored duplicates.

// Home module keys (stable; staff see the label, never the key).
const (
	ModHero        = "hero"
	ModDeals       = "deals"
	ModContinue    = "continue-shopping"
	ModRecent      = "recently-viewed"
	ModDepartments = "department-tiles"
	ModGroceries   = "groceries-rail"
	ModServices    = "services-near-you"
	ModCommunities = "communities"
	ModNewArrivals = "new-arrivals"
	ModTopSellers  = "top-sellers"
	ModPreorder    = "preorder"
	ModPlus        = "plus"
)

// Reason codes of a decision (the Detail sentence is what staff read).
const (
	ReasonShown      = "shown"
	ReasonDisabled   = "disabled-by-staff"
	ReasonThin       = "not-enough-products"
	ReasonFewSellers = "not-enough-sellers"
	ReasonStale      = "not-fresh-enough"
	ReasonNoCover    = "not-enough-departments"
	ReasonNoSignal   = "no-shopper-signal"
	ReasonNothing    = "nothing-to-show"
)

// UnitSignal is a group (department or community) with its eligible product and distinct seller counts.
type UnitSignal struct {
	Name     string
	Products int
	Sellers  int
}

// HomeSignals is everything the eligibility pass needs; the gateway fills it from search facets and a few cheap reads.
type HomeSignals struct {
	EligibleProducts  int
	Sellers           int
	Departments       []UnitSignal
	Communities       []UnitSignal
	GroceryProducts   int
	DealProducts      int // eligible and at least the deal threshold off
	NewestAgeDays     int // age of the newest eligible product; 0 = unknown
	NewProducts       int // eligible products listed within the freshness window
	TopSellerProducts int // eligible products with a real rating (never "0 reviews")
	ServiceProviders  int // providers who serve the shopper's place
	PreorderWindows   int // open community pre-order windows
	HeroSlides        int // scheduled, approved campaign slides
	PlusEnabled       bool
	SignedIn          bool
	RecentlyViewed    int // eligible remembered products
	OrdersBought      int // distinct products the shopper bought
}

// ModuleRule is one staff-editable row (settings). Zero means "no requirement".
type ModuleRule struct {
	Enabled        bool
	MinProducts    int
	MinSellers     int
	MinUnits       int // distinct departments / qualifying communities the module needs
	UnitMinProduct int // a community or department counts only with this many eligible products
	UnitMinSellers int
	MaxAgeDays     int
	Weight         int // base order, higher first
}

// ModuleDecision is the outcome for one module: shown or hidden, in what position, and why in staff words.
type ModuleDecision struct {
	Module string
	Label  string
	Shown  bool
	Order  int
	Reason string
	Detail string
	Units  []string // the departments / communities the module will show
}

// HomeEligibility decides which modules the home shows for a set of signals.
type HomeEligibility interface {
	Evaluate(sig HomeSignals, rules map[string]ModuleRule) []ModuleDecision
}

type homeEligibilityImpl struct{}

var _ HomeEligibility = (*homeEligibilityImpl)(nil)

// NewHomeEligibility returns the rule engine.
func NewHomeEligibility() HomeEligibility { return &homeEligibilityImpl{} }

var moduleLabels = map[string]string{
	ModHero: "Hero campaigns", ModDeals: "Deals", ModContinue: "Continue shopping", ModRecent: "Recently viewed",
	ModDepartments: "Shop by department", ModGroceries: "Groceries", ModServices: "Services near you",
	ModCommunities: "Shop by community", ModNewArrivals: "New arrivals", ModTopSellers: "Top sellers",
	ModPreorder: "Pre-order", ModPlus: "AfroTransact Plus",
}

// DefaultHomeRules are the starting thresholds (all editable in the staff console).
func DefaultHomeRules() map[string]ModuleRule {
	return map[string]ModuleRule{
		ModHero:        {Enabled: true, Weight: 100},
		ModContinue:    {Enabled: true, MinProducts: 1, Weight: 95},
		ModRecent:      {Enabled: true, MinProducts: 1, Weight: 90},
		ModDeals:       {Enabled: true, MinProducts: 6, MinSellers: 2, Weight: 85},
		ModDepartments: {Enabled: true, MinUnits: 2, UnitMinProduct: 1, Weight: 80},
		ModGroceries:   {Enabled: true, MinProducts: 6, Weight: 75},
		ModServices:    {Enabled: true, MinProducts: 1, Weight: 70},
		ModNewArrivals: {Enabled: true, MinProducts: 8, MaxAgeDays: 30, Weight: 60},
		ModTopSellers:  {Enabled: true, MinProducts: 8, MinSellers: 2, Weight: 55},
		ModPreorder:    {Enabled: true, MinProducts: 1, Weight: 50},
		ModCommunities: {Enabled: true, MinUnits: 3, UnitMinProduct: 8, UnitMinSellers: 2, Weight: 40},
		ModPlus:        {Enabled: true, Weight: 10},
	}
}

// Evaluate returns one decision per module: shown modules first in weight order, then the hidden ones with their reason.
func (*homeEligibilityImpl) Evaluate(sig HomeSignals, rules map[string]ModuleRule) []ModuleDecision {
	out := make([]ModuleDecision, 0, len(moduleLabels))
	for key, label := range moduleLabels {
		r, ok := rules[key]
		d := ModuleDecision{Module: key, Label: label, Order: r.Weight}
		if !ok || !r.Enabled {
			d.Reason, d.Detail = ReasonDisabled, label+" is switched off in the home settings."
		} else {
			d = decide(d, key, r, sig)
		}
		out = append(out, d)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Shown != out[j].Shown {
			return out[i].Shown
		}
		if out[i].Order != out[j].Order {
			return out[i].Order > out[j].Order
		}
		return out[i].Label < out[j].Label
	})
	return out
}

func decide(d ModuleDecision, key string, r ModuleRule, sig HomeSignals) ModuleDecision {
	show := func(detail string) ModuleDecision { d.Shown, d.Reason, d.Detail = true, ReasonShown, detail; return d }
	hide := func(reason, detail string) ModuleDecision { d.Reason, d.Detail = reason, detail; return d }
	switch key {
	case ModHero:
		if sig.HeroSlides == 0 {
			return hide(ReasonNothing, "No approved campaign slide is scheduled for today.")
		}
		return show(fmt.Sprintf("%d scheduled slides.", sig.HeroSlides))
	case ModPlus:
		if !sig.PlusEnabled {
			return hide(ReasonNothing, "AfroTransact Plus is not on sale in this market.")
		}
		return show("Membership band.")
	case ModContinue:
		if !sig.SignedIn || sig.OrdersBought == 0 {
			return hide(ReasonNoSignal, "Needs a signed-in shopper with an order.")
		}
		return show(fmt.Sprintf("Built from %d products the shopper bought.", sig.OrdersBought))
	case ModRecent:
		if sig.RecentlyViewed < r.MinProducts {
			return hide(ReasonNoSignal, "The visitor has not viewed an eligible product yet.")
		}
		return show(fmt.Sprintf("%d remembered products.", sig.RecentlyViewed))
	case ModServices:
		if sig.ServiceProviders < r.MinProducts {
			return hide(ReasonNothing, "No provider serves the visitor's place yet.")
		}
		return show(fmt.Sprintf("%d providers serve this place.", sig.ServiceProviders))
	case ModPreorder:
		if sig.PreorderWindows < r.MinProducts {
			return hide(ReasonNothing, "No pre-order window is open.")
		}
		return show(fmt.Sprintf("%d open windows.", sig.PreorderWindows))
	case ModDepartments:
		return units(d, r, sig.Departments, ReasonNoCover, "departments")
	case ModCommunities:
		return units(d, r, sig.Communities, ReasonThin, "communities")
	}
	have := map[string]int{ModDeals: sig.DealProducts, ModGroceries: sig.GroceryProducts, ModNewArrivals: sig.NewProducts, ModTopSellers: sig.TopSellerProducts}[key]
	if have < r.MinProducts {
		return hide(ReasonThin, fmt.Sprintf("%s needs %d eligible products; %d are in stock with a photo.", d.Label, r.MinProducts, have))
	}
	if r.MinSellers > 0 && sig.Sellers < r.MinSellers {
		return hide(ReasonFewSellers, fmt.Sprintf("%s needs products from %d sellers; there are %d.", d.Label, r.MinSellers, sig.Sellers))
	}
	if r.MaxAgeDays > 0 && sig.NewestAgeDays > r.MaxAgeDays {
		return hide(ReasonStale, fmt.Sprintf("The newest product is %d days old; the limit is %d.", sig.NewestAgeDays, r.MaxAgeDays))
	}
	return show(fmt.Sprintf("%d eligible products.", have))
}

// units evaluates the department and community modules: a unit counts only above its own thresholds, and the module
// needs MinUnits of them. Units are listed biggest first, which is the order the tiles use.
func units(d ModuleDecision, r ModuleRule, all []UnitSignal, reason, noun string) ModuleDecision {
	var ok []UnitSignal
	for _, u := range all {
		if u.Products >= r.UnitMinProduct && u.Sellers >= r.UnitMinSellers && u.Name != "" {
			ok = append(ok, u)
		}
	}
	sort.SliceStable(ok, func(i, j int) bool { return ok[i].Products > ok[j].Products })
	if len(ok) < r.MinUnits {
		d.Reason = reason
		d.Detail = fmt.Sprintf("%s needs %d %s with %d eligible products and %d sellers each; %d qualify.", d.Label, r.MinUnits, noun, r.UnitMinProduct, r.UnitMinSellers, len(ok))
		return d
	}
	d.Shown, d.Reason = true, ReasonShown
	for _, u := range ok {
		d.Units = append(d.Units, u.Name)
	}
	d.Detail = fmt.Sprintf("%d %s qualify.", len(ok), noun)
	return d
}
