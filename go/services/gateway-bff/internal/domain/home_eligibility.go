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
	ModDeptRail    = "department-rail"
	ModBrandHero   = "brand-hero"
	ModSellBand    = "sell-band"
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
	ReasonSponsored  = "sponsored-cap"
	ReasonPageFull   = "page-full"
)

// UnitSignal is a group (department or community) with its eligible product and distinct seller counts.
type UnitSignal struct {
	Name     string
	Products int
	Sellers  int
}

// HomeSignals is everything the eligibility pass needs; the gateway fills it from search facets and a few cheap reads.
type HomeSignals struct {
	EligibleProducts    int
	Sellers             int
	Departments         []UnitSignal
	Communities         []UnitSignal
	GroceryProducts     int
	DealProducts        int    // eligible and at least the deal threshold off
	NewestAgeDays       int    // age of the newest eligible product; 0 = unknown
	NewProducts         int    // eligible products listed within the freshness window
	TopSellerProducts   int    // eligible products with a real rating (never "0 reviews")
	ServiceProviders    int    // providers who serve the shopper's place
	ServiceProvidersAll int    // providers anywhere in the market (the popular-services fallback and the nav entry)
	GroceryDepartment   string // name of the groceries department (its rail is the groceries module, not a "Top in" rail)
	PreorderWindows     int    // open community pre-order windows
	HeroSlides          int    // scheduled, approved campaign slides
	PlusEnabled         bool
	SignedIn            bool
	RecentlyViewed      int // eligible remembered products
	OrdersBought        int // distinct products the shopper bought
}

// ModuleRule is one staff-editable row (settings). Zero means "no requirement".
type ModuleRule struct {
	Enabled        bool `json:"enabled"`
	MinProducts    int  `json:"minProducts"`
	MinSellers     int  `json:"minSellers"`
	MinUnits       int  `json:"minUnits"`        // distinct departments / qualifying communities the module needs
	UnitMinProduct int  `json:"unitMinProducts"` // a community or department counts only with this many eligible products
	UnitMinSellers int  `json:"unitMinSellers"`
	MaxAgeDays     int  `json:"maxAgeDays"`
	Weight         int  `json:"weight"` // base order, higher first
	// Show is how many items the module shows (its variety pool is poolMultiplier times this); Band is the order band.
	Show int    `json:"show"`
	Band string `json:"band"`
}

// ModuleDecision is the outcome for one module: shown or hidden, in what position, and why in staff words.
type ModuleDecision struct {
	Module string   `json:"module"`
	Label  string   `json:"label"`
	Shown  bool     `json:"shown"`
	Order  int      `json:"order"`
	Reason string   `json:"reason"`
	Detail string   `json:"detail"`
	Units  []string `json:"units,omitempty"` // the departments / communities the module will show
	// Have and Need are the live count against the threshold; Pool and Picked are filled by the composer (variety).
	Have   int    `json:"have"`
	Need   int    `json:"need"`
	Pool   int    `json:"pool"`
	Picked int    `json:"picked"`
	Band   string `json:"band,omitempty"`
	Pinned bool   `json:"pinned,omitempty"` // a staff placement of the same kind wins over this automatic module
}

// HomeEligibility decides which modules the home shows for a set of signals.
type HomeEligibility interface {
	Evaluate(sig HomeSignals, rules map[string]ModuleRule) []ModuleDecision
	// EvaluateWith adds hysteresis: a module in prev (shown last time) stays shown until its product counts fall
	// `hysteresis` below the minimum, so a module at the boundary does not flap between loads (S416).
	EvaluateWith(sig HomeSignals, rules map[string]ModuleRule, prev map[string]bool, hysteresis int) []ModuleDecision
}

type homeEligibilityImpl struct{}

var _ HomeEligibility = (*homeEligibilityImpl)(nil)

// NewHomeEligibility returns the rule engine.
func NewHomeEligibility() HomeEligibility { return &homeEligibilityImpl{} }

var moduleLabels = map[string]string{
	ModHero: "Hero campaigns", ModDeals: "Deals", ModContinue: "Continue shopping", ModRecent: "Recently viewed",
	ModDepartments: "Shop by department", ModGroceries: "Groceries", ModServices: "Services near you",
	ModCommunities: "Shop by community", ModNewArrivals: "New arrivals", ModTopSellers: "Top sellers",
	ModPreorder: "Pre-order", ModPlus: "AfroTransact Plus", ModDeptRail: "Top in a department",
	ModBrandHero: "Brand hero", ModSellBand: "Sell on AfroTransact",
}

// ModuleLabels is the name staff read for every module key (never the key itself).
func ModuleLabels() map[string]string {
	out := make(map[string]string, len(moduleLabels))
	for k, v := range moduleLabels {
		out[k] = v
	}
	return out
}

// IsModule reports whether key is a registry module.
func IsModule(key string) bool { _, ok := moduleLabels[key]; return ok }

// DefaultHomeRules are the starting thresholds (all editable in the staff console).
func DefaultHomeRules() map[string]ModuleRule {
	return map[string]ModuleRule{
		ModHero:        {Enabled: true, Weight: 100, Show: 5, Band: BandHero},
		ModBrandHero:   {Enabled: true, Weight: 99, Show: 1, Band: BandHero},
		ModContinue:    {Enabled: true, MinProducts: 1, Weight: 95, Show: 12, Band: BandPersonal},
		ModRecent:      {Enabled: true, MinProducts: 1, Weight: 90, Show: 12, Band: BandPersonal},
		ModDeals:       {Enabled: true, MinProducts: 6, MinSellers: 2, Weight: 85, Show: 8, Band: BandDiscovery},
		ModDepartments: {Enabled: true, MinUnits: 2, UnitMinProduct: 1, Weight: 80, Show: 8, Band: BandDiscovery},
		ModGroceries:   {Enabled: true, MinProducts: 6, Weight: 75, Show: 8, Band: BandDiscovery},
		ModServices:    {Enabled: true, MinProducts: 1, Weight: 70, Show: 6, Band: BandDiscovery},
		ModNewArrivals: {Enabled: true, MinProducts: 8, MaxAgeDays: 30, Weight: 60, Show: 8, Band: BandDiscovery},
		ModTopSellers:  {Enabled: true, MinProducts: 8, MinSellers: 2, Weight: 55, Show: 8, Band: BandDiscovery},
		ModDeptRail:    {Enabled: true, MinProducts: 6, MinUnits: 1, UnitMinProduct: 6, Weight: 52, Show: 8, Band: BandDiscovery},
		ModPreorder:    {Enabled: true, MinProducts: 1, Weight: 50, Show: 6, Band: BandPromo},
		ModCommunities: {Enabled: true, MinUnits: 3, UnitMinProduct: 8, UnitMinSellers: 2, Weight: 40, Show: 6, Band: BandDiscovery},
		ModPlus:        {Enabled: true, Weight: 10, Show: 1, Band: BandPromo},
		ModSellBand:    {Enabled: true, Weight: 5, Show: 1, Band: BandBrand},
	}
}

// Evaluate returns one decision per module: shown modules first in weight order, then the hidden ones with their reason.
func (e *homeEligibilityImpl) Evaluate(sig HomeSignals, rules map[string]ModuleRule) []ModuleDecision {
	return e.EvaluateWith(sig, rules, nil, 0)
}

func (*homeEligibilityImpl) EvaluateWith(sig HomeSignals, rules map[string]ModuleRule, prev map[string]bool, hysteresis int) []ModuleDecision {
	out := make([]ModuleDecision, 0, len(moduleLabels))
	for key, label := range moduleLabels {
		r, ok := rules[key]
		d := ModuleDecision{Module: key, Label: label, Order: r.Weight, Band: r.Band}
		if !ok || !r.Enabled {
			d.Reason, d.Detail = ReasonDisabled, label+" is switched off in the home settings."
		} else {
			if prev[key] && hysteresis > 0 { // shown last time: allow it to sit a little below its minimum
				r.MinProducts = max(0, r.MinProducts-hysteresis)
				if r.UnitMinProduct > 1 {
					r.UnitMinProduct = max(1, r.UnitMinProduct-hysteresis)
				}
			}
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
	case ModBrandHero:
		if sig.HeroSlides > 0 {
			return hide(ReasonNothing, "A campaign hero is scheduled, so the plain brand hero is not needed.")
		}
		return show("No campaign hero is scheduled: the text-only brand hero is shown (no photo, no promise of stock).")
	case ModSellBand:
		return show("Invites sellers and service providers to join.")
	case ModDeptRail:
		var rest []UnitSignal
		for _, u := range sig.Departments {
			if u.Name != sig.GroceryDepartment {
				rest = append(rest, u)
			}
		}
		return units(d, ModuleRule{MinUnits: r.MinUnits, UnitMinProduct: max(r.UnitMinProduct, r.MinProducts)}, rest, ReasonThin, "departments")
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
		return show("A slot for the browser's own history: it hides itself when the visitor has none.")
	case ModServices:
		d.Have, d.Need = max(sig.ServiceProviders, sig.ServiceProvidersAll), r.MinProducts
		if sig.ServiceProviders >= r.MinProducts && sig.ServiceProviders > 0 {
			return show(fmt.Sprintf("%d providers serve this place.", sig.ServiceProviders))
		}
		if sig.ServiceProvidersAll >= r.MinProducts && sig.ServiceProvidersAll > 0 {
			return show(fmt.Sprintf("No provider serves this place yet; %d providers are listed in the market (services you can book).", sig.ServiceProvidersAll))
		}
		return hide(ReasonNothing, "No service provider is listed in this market yet.")
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
	d.Have, d.Need = have, r.MinProducts
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
	d.Have, d.Need = len(ok), r.MinUnits
	if len(ok) < r.MinUnits {
		d.Reason = reason
		need := fmt.Sprintf("with %d eligible products", r.UnitMinProduct)
		if r.UnitMinProduct == 1 {
			need = "with an eligible product"
		}
		if r.UnitMinSellers > 0 {
			need += fmt.Sprintf(" and %d sellers each", r.UnitMinSellers)
		} else if r.UnitMinProduct > 1 {
			need += " each"
		}
		d.Detail = fmt.Sprintf("%s needs %d %s %s; %d qualify.", d.Label, r.MinUnits, noun, need, len(ok))
		return d
	}
	d.Shown, d.Reason = true, ReasonShown
	for _, u := range ok {
		d.Units = append(d.Units, u.Name)
	}
	d.Detail = fmt.Sprintf("%d %s qualify.", len(ok), noun)
	return d
}
