package domain

import (
	"fmt"
	"sort"
	"strings"
)

// Order bands of the home. Bands keep their order; inside a discovery band the modules rotate by visit (home_variety.go).
const (
	BandHero      = "hero"
	BandPersonal  = "personal"
	BandDiscovery = "discovery"
	BandPromo     = "promo"
	BandBrand     = "brand"
)

// bandRank is the page position of a band.
var bandRank = map[string]int{BandHero: 0, BandPersonal: 1, BandDiscovery: 2, BandPromo: 3, BandBrand: 4}

// Home dynamic modes.
const (
	DynamicOff    = "off"
	DynamicShadow = "shadow"
	DynamicOn     = "on"
)

// LocaleLabels is a label per locale (en, fr, es, ar, yo); English is required and the fallback.
type LocaleLabels map[string]string

// Locales are the five storefront languages.
var Locales = []string{"en", "fr", "es", "ar", "yo"}

// For returns the label for a locale, falling back to English, then to fallback.
func (l LocaleLabels) For(locale, fallback string) string {
	if v := strings.TrimSpace(l[locale]); v != "" {
		return v
	}
	if v := strings.TrimSpace(l["en"]); v != "" {
		return v
	}
	return fallback
}

// HomeCaps are the staff-editable variety and page limits (contract HomeCaps; defaults in DefaultHomeCaps).
type HomeCaps struct {
	MaxModules         int     `json:"maxModules"`
	MinModules         int     `json:"minModules"`
	SellerShare        int     `json:"sellerShare"`
	SponsoredPerOrg    int     `json:"sponsoredPerOrganic"`
	PoolMultiplier     int     `json:"poolMultiplier"`
	NoveltyLoads       int     `json:"noveltyLoads"`
	NoveltyPenalty     float64 `json:"noveltyPenalty"`
	VisitWindowMinutes int     `json:"visitWindowMinutes"`
	Hysteresis         int     `json:"hysteresis"`
	DealMinPercent     int     `json:"dealMinPercent"`
	NavBarSize         int     `json:"navBarSize"`
}

// DefaultHomeCaps are the documented defaults.
func DefaultHomeCaps() HomeCaps {
	return HomeCaps{MaxModules: 12, MinModules: 3, SellerShare: 2, SponsoredPerOrg: 3, PoolMultiplier: 3, NoveltyLoads: 3,
		NoveltyPenalty: 0.6, VisitWindowMinutes: 30, Hysteresis: 2, DealMinPercent: 10, NavBarSize: 7}
}

// HomeSettings are the staff-editable home settings as content stores them (contract HomeSettings).
type HomeSettings struct {
	Version int `json:"version"`
	// Dynamic is off, shadow or on; nil follows the environment default (EffectiveDynamic).
	Dynamic          *string                 `json:"dynamic"`
	Modules          map[string]ModuleRule   `json:"modules"`
	Caps             HomeCaps                `json:"caps"`
	DepartmentLabels map[string]LocaleLabels `json:"departmentLabels"`
	UpdatedAt        string                  `json:"updatedAt,omitempty"`
	UpdatedBy        string                  `json:"updatedBy,omitempty"`
}

// DefaultHomeSettings is what loads when no row exists (and fills any key content does not send).
func DefaultHomeSettings() HomeSettings {
	return HomeSettings{Modules: DefaultHomeRules(), Caps: DefaultHomeCaps(), DepartmentLabels: map[string]LocaleLabels{}}
}

// Merged returns s with every missing module and zero cap filled from the defaults, so an older content service or a
// partial row never leaves the gateway without a rule.
func (s HomeSettings) Merged() HomeSettings {
	d := DefaultHomeSettings()
	out := s
	out.Modules = map[string]ModuleRule{}
	for k, r := range d.Modules {
		if v, ok := s.Modules[k]; ok {
			if v.Show == 0 {
				v.Show = r.Show
			}
			if v.Band == "" {
				v.Band = r.Band
			}
			out.Modules[k] = v
		} else {
			out.Modules[k] = r
		}
	}
	c, dc := s.Caps, d.Caps
	fill := func(v *int, def int) {
		if *v == 0 {
			*v = def
		}
	}
	fill(&c.MaxModules, dc.MaxModules)
	fill(&c.SellerShare, dc.SellerShare)
	fill(&c.SponsoredPerOrg, dc.SponsoredPerOrg)
	fill(&c.PoolMultiplier, dc.PoolMultiplier)
	fill(&c.VisitWindowMinutes, dc.VisitWindowMinutes)
	fill(&c.DealMinPercent, dc.DealMinPercent)
	fill(&c.NavBarSize, dc.NavBarSize)
	if s.Version == 0 && c.NoveltyPenalty == 0 {
		c.NoveltyPenalty = dc.NoveltyPenalty
		c.NoveltyLoads, c.MinModules, c.Hysteresis = dc.NoveltyLoads, dc.MinModules, dc.Hysteresis
	}
	out.Caps = c
	if out.DepartmentLabels == nil {
		out.DepartmentLabels = map[string]LocaleLabels{}
	}
	return out
}

// EffectiveDynamic is the mode the page runs: the stored value, else on in local, dev, uat and trial and off in prod
// (and any other environment) until the owner switches it.
func EffectiveDynamic(stored *string, env string) string {
	if stored != nil {
		switch *stored {
		case DynamicOff, DynamicShadow, DynamicOn:
			return *stored
		}
	}
	switch strings.ToLower(env) {
	case "local", "dev", "uat", "trial":
		return DynamicOn
	}
	return DynamicOff
}

// ValidateHomeSettings returns a message per invalid field (empty = valid): negative thresholds, a weight outside 0 to 100,
// an unknown module or band, a cap outside its range.
func ValidateHomeSettings(s HomeSettings) map[string]string {
	bad := map[string]string{}
	if s.Dynamic != nil && *s.Dynamic != DynamicOff && *s.Dynamic != DynamicShadow && *s.Dynamic != DynamicOn {
		bad["dynamic"] = "Must be off, shadow or on"
	}
	keys := make([]string, 0, len(s.Modules))
	for k := range s.Modules {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		r := s.Modules[k]
		f := "modules." + k
		if !IsModule(k) {
			bad[f] = "Unknown module"
			continue
		}
		for name, v := range map[string]int{"minProducts": r.MinProducts, "minSellers": r.MinSellers, "minUnits": r.MinUnits,
			"unitMinProducts": r.UnitMinProduct, "unitMinSellers": r.UnitMinSellers, "maxAgeDays": r.MaxAgeDays} {
			if v < 0 {
				bad[f+"."+name] = "Cannot be negative"
			}
		}
		if r.Weight < 0 || r.Weight > 100 {
			bad[f+".weight"] = "Must be between 0 and 100"
		}
		if r.Show < 0 || r.Show > 24 {
			bad[f+".show"] = "Must be between 1 and 24"
		}
		if _, ok := bandRank[r.Band]; r.Band != "" && !ok {
			bad[f+".band"] = "Must be hero, personal, discovery, promo or brand"
		}
	}
	c := s.Caps
	rng := func(name string, v, lo, hi int) {
		if v != 0 && (v < lo || v > hi) {
			bad["caps."+name] = fmt.Sprintf("Must be between %d and %d", lo, hi)
		}
	}
	rng("maxModules", c.MaxModules, 3, 24)
	rng("minModules", c.MinModules, 0, 12)
	rng("sellerShare", c.SellerShare, 1, 24)
	rng("sponsoredPerOrganic", c.SponsoredPerOrg, 1, 10)
	rng("poolMultiplier", c.PoolMultiplier, 1, 10)
	rng("noveltyLoads", c.NoveltyLoads, 0, 10)
	rng("visitWindowMinutes", c.VisitWindowMinutes, 5, 240)
	rng("hysteresis", c.Hysteresis, 0, 20)
	rng("dealMinPercent", c.DealMinPercent, 1, 90)
	rng("navBarSize", c.NavBarSize, 3, 12)
	if c.NoveltyPenalty < 0 || c.NoveltyPenalty > 1 {
		bad["caps.noveltyPenalty"] = "Must be between 0 and 1"
	}
	for k, l := range s.DepartmentLabels {
		if strings.TrimSpace(l["en"]) == "" {
			bad["departmentLabels."+k] = "An English label is required"
		}
	}
	return bad
}
