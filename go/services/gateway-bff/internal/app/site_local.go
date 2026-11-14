package app

import (
	"context"
	"hash/fnv"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Location-aware home rules. The shopper's deliver-to place (city, state, ZIP) and a daily seed steer what every rule
// picks: products that ship from the shopper's city come first, then their state, then their region; within the same
// closeness the order is a deterministic daily rotation seeded by (day, ZIP, shopper), so the page differs by ZIP and by
// day but never changes between two loads of the same visit. Nothing here is specific to a category or a ZIP.

const (
	// localPool is how many eligible candidates a rule collects before ranking and cutting to its limit.
	localPool = 36
	// rotationBucket is how many neighbours in the search ranking are shuffled together, so a rotation never sends a
	// poorly ranked product to the top.
	rotationBucket = 4
	// defaultFastDays is the longest promise a deliverable-fast source accepts when it does not say.
	defaultFastDays = 2

	tierCity, tierState, tierRegion, tierFar = 0, 1, 2, 3
)

// shopperScope is who the page is for. The zero value ranks nothing and rotates nothing.
type shopperScope struct {
	loc  domain.DeliveryLocation
	seed string
}

// newScope seeds the day's rotation with the UTC day, the deliver-to ZIP and the signed-in shopper (or the guest
// session).
func newScope(loc domain.DeliveryLocation, c *domain.Caller, now time.Time) shopperScope {
	user := ""
	if c != nil {
		user = c.Subject
		if user == "" && c.Session != nil {
			user = c.Session.ID
		}
	}
	return shopperScope{loc: loc, seed: now.UTC().Format("2006-01-02") + "|" + loc.ZIP + "|" + user}
}

func (sc shopperScope) hash(atin string) uint64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(sc.seed + "|" + atin))
	// FNV's low bits barely depend on the early bytes (the seed); a finalizer mixes them so another day or ZIP reorders.
	x := h.Sum64()
	x ^= x >> 33
	x *= 0xff51afd7ed558ccd
	x ^= x >> 33
	x *= 0xc4ceb9fe1a85ec53
	return x ^ x>>33
}

// usRegions groups the states into the four census regions ("nearest" when nothing ships from the shopper's state).
var usRegions = map[string]string{
	"CT": "NE", "ME": "NE", "MA": "NE", "NH": "NE", "RI": "NE", "VT": "NE", "NJ": "NE", "NY": "NE", "PA": "NE",
	"IL": "MW", "IN": "MW", "MI": "MW", "OH": "MW", "WI": "MW", "IA": "MW", "KS": "MW", "MN": "MW", "MO": "MW", "NE": "MW", "ND": "MW", "SD": "MW",
	"DE": "S", "DC": "S", "FL": "S", "GA": "S", "MD": "S", "NC": "S", "SC": "S", "VA": "S", "WV": "S", "AL": "S", "KY": "S", "MS": "S", "TN": "S",
	"AR": "S", "LA": "S", "OK": "S", "TX": "S",
	"AZ": "W", "CO": "W", "ID": "W", "MT": "W", "NV": "W", "NM": "W", "UT": "W", "WY": "W", "AK": "W", "CA": "W", "HI": "W", "OR": "W", "WA": "W",
}

// shipsFromPlace reads where a card ships from: the offer's "Houston, TX" label, else the seller's city.
func shipsFromPlace(p domain.ProductSummary) (city, state string) {
	label := strings.TrimSpace(p.ShipsFrom)
	if label == "" {
		return strings.TrimSpace(p.SellerCity), ""
	}
	city, state, _ = strings.Cut(label, ",")
	city, state = strings.TrimSpace(city), strings.ToUpper(strings.TrimSpace(state))
	if len(state) != 2 {
		state = ""
	}
	return city, state
}

// locationTier says how close the product ships from to the shopper: city, state, region, or far/unknown.
func locationTier(p domain.ProductSummary, loc domain.DeliveryLocation) int {
	city, state := shipsFromPlace(p)
	switch {
	case city != "" && loc.City != "" && strings.EqualFold(city, loc.City):
		return tierCity
	case state != "" && loc.State != "" && strings.EqualFold(state, loc.State):
		return tierState
	case state != "" && usRegions[state] != "" && usRegions[state] == usRegions[strings.ToUpper(loc.State)]:
		return tierRegion
	}
	return tierFar
}

// nearShopper keeps what ships from the shopper's city, state or region. Without a known place nothing is near.
func nearShopper(in []domain.ProductSummary, loc domain.DeliveryLocation) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	if loc.City == "" && loc.State == "" {
		return out
	}
	for _, p := range in {
		if locationTier(p, loc) < tierFar {
			out = append(out, p)
		}
	}
	return out
}

// rankForShopper orders candidates by closeness to the shopper, then by the daily rotation inside each bucket of
// neighbouring search ranks. Stable for a given (day, ZIP, shopper); a zero scope keeps the search order.
func rankForShopper(in []domain.ProductSummary, sc shopperScope) []domain.ProductSummary {
	if sc.seed == "" || len(in) < 2 {
		return in
	}
	type keyed struct {
		p            domain.ProductSummary
		tier, bucket int
		h            uint64
	}
	ks := make([]keyed, len(in))
	for i, p := range in {
		ks[i] = keyed{p: p, tier: locationTier(p, sc.loc), bucket: i / rotationBucket, h: sc.hash(p.ATIN)}
	}
	sort.SliceStable(ks, func(i, j int) bool {
		a, b := ks[i], ks[j]
		if a.tier != b.tier {
			return a.tier < b.tier
		}
		if a.bucket != b.bucket {
			return a.bucket < b.bucket
		}
		return a.h < b.h
	})
	out := make([]domain.ProductSummary, len(ks))
	for i, k := range ks {
		out[i] = k.p
	}
	return out
}

// withinDays asks for the real delivery window of each product to the shopper's ZIP and keeps those that arrive within
// maxDays (default 2), carrying the window on the card. A product with no real basis for a date (a seller-shipped offer has
// no ship-from on a card, no ZIP, no promise service) does not qualify: nothing is guessed.
func (s *siteServiceImpl) withinDays(ctx context.Context, in []domain.ProductSummary, zip string, maxDays int, now time.Time) ([]domain.ProductSummary, error) {
	if s.d.Arrival == nil || zip == "" || len(in) == 0 {
		return []domain.ProductSummary{}, nil
	}
	if maxDays <= 0 {
		maxDays = defaultFastDays
	}
	reqs := make([]ArrivalRequest, len(in))
	for i, p := range in {
		reqs[i] = ArrivalRequest{Key: p.ATIN, Fulfilment: p.Fulfilment, SellerOrgID: p.SellerID}
	}
	windows := s.d.Arrival.Windows(ctx, zip, reqs, now)
	out := []domain.ProductSummary{}
	for _, p := range in {
		if w := windows[p.ATIN]; w.Known() && w.Days <= maxDays {
			p.Delivery = s.d.Estimator.Promise(p.Fulfilment).WithArrival(w)
			out = append(out, p)
		}
	}
	return out, nil
}

// railTitle fills {city}, {state} and {day} (the day by which everything in the rail arrives, from the real windows on its
// cards; with none the " by {day}" is dropped rather than invented).
func (s *siteServiceImpl) railTitle(title string, loc domain.DeliveryLocation, items []domain.ProductSummary, now time.Time) string {
	title = strings.ReplaceAll(title, "{city}", firstNonEmpty(loc.City, "you"))
	title = strings.ReplaceAll(title, "{state}", firstNonEmpty(loc.State, "your state"))
	if strings.Contains(title, "{day}") {
		latest := ""
		for _, p := range items {
			if p.Delivery.Basis != domain.ArrivalPromise {
				latest = ""
				break
			}
			latest = max(latest, p.Delivery.LatestDate)
		}
		if t, err := time.Parse(time.DateOnly, latest); err == nil {
			title = strings.ReplaceAll(title, "{day}", t.Format("Monday, Jan 2"))
		} else {
			title = strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(title, " by {day}", ""), "{day}", ""))
		}
	}
	return title
}

// dedupeRails removes from every rail what an earlier part of the page (tiles, the rails before it) already shows and
// hides the rails left empty, so a product is on the page once.
func dedupeRails(rails []*domain.Rail, used map[string]bool) []*domain.Rail {
	for i, r := range rails {
		if r == nil {
			continue
		}
		kept := make([]domain.ProductSummary, 0, len(r.Items))
		for _, p := range r.Items {
			if !used[p.ATIN] {
				used[p.ATIN] = true
				kept = append(kept, p)
			}
		}
		if len(kept) == 0 {
			rails[i] = nil
			continue
		}
		r.Items = kept
	}
	return rails
}
