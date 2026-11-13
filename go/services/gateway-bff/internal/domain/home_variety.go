package domain

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"math"
	"sort"
	"strings"
)

// The variety engine of the dynamic home (docs/engineering/dynamic-home.md). Every function here is pure: the same
// inputs always give the same output, so a visit is stable (refresh and back do not reshuffle) and staff can replay one.
//
//   - A module draws from a pool larger than it shows and picks a subset by a seeded, quality-weighted shuffle
//     (Efraimidis-Spirakis: key = ln(u) / weight, highest first).
//   - The seed is visitor + visit window (+ variant + module), so it changes when a new visit starts.
//   - Items shown on recent loads are down-weighted (novelty); items in Recently viewed or the cart are excluded.
//   - One seller supplies at most SellerShare items of a module.
//   - Crawlers get Deterministic: quality order, no randomness, no novelty.

// SeedOf hashes the parts into a 64-bit seed.
func SeedOf(parts ...string) uint64 {
	sum := sha256.Sum256([]byte(strings.Join(parts, "\x1f")))
	return binary.BigEndian.Uint64(sum[:8])
}

// SeedLabel is the printable form of a seed, as staff copy it from debug mode.
func SeedLabel(seed uint64) string {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], seed)
	return hex.EncodeToString(b[:])
}

// ParseSeedLabel reverses SeedLabel; ok is false for anything that is not a seed label.
func ParseSeedLabel(s string) (uint64, bool) {
	b, err := hex.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != 8 {
		return 0, false
	}
	return binary.BigEndian.Uint64(b), true
}

// ShortKey is the 8-character key an item is remembered by in the novelty memory (never the product id itself).
func ShortKey(id string) string {
	sum := sha256.Sum256([]byte("seen\x1f" + id))
	return hex.EncodeToString(sum[:4])
}

// unit maps (seed, key) to a number in (0, 1).
func unit(seed uint64, key string) float64 {
	sum := sha256.Sum256([]byte(SeedLabel(seed) + "\x1f" + key))
	v := binary.BigEndian.Uint64(sum[:8]) >> 11 // 53 bits
	return (float64(v) + 0.5) / float64(uint64(1)<<53)
}

// VarietyItem is one candidate of a module's pool.
type VarietyItem struct {
	Key     string  // product id, department key or service id
	Seller  string  // seller (or provider) key for the share cap; empty = no cap
	Quality float64 // 0..1, see QualityOf
}

// VarietyParams drive one module's pick.
type VarietyParams struct {
	Seed           uint64
	Module         string
	Show           int
	Novelty        map[string]bool // ShortKey of items shown on previous loads
	NoveltyPenalty float64         // 0.6: a remembered item keeps 40 percent of its chance
	Exclude        map[string]bool // keys already on the page, in Recently viewed or in the cart
	SellerShare    int             // most items of one seller; 0 = no cap
	Deterministic  bool
}

// PickVariety returns up to p.Show items: the seeded weighted shuffle of the pool, minus excluded items, with at most
// SellerShare per seller. The share relaxes only when the pool does not have enough distinct sellers to fill the module
// (a marketplace with one seller still gets a page), never otherwise.
func PickVariety(pool []VarietyItem, p VarietyParams) []VarietyItem {
	items := make([]VarietyItem, 0, len(pool))
	sellers := map[string]int{}
	seenKey := map[string]bool{}
	for _, it := range pool {
		if it.Key == "" || p.Exclude[it.Key] || seenKey[it.Key] {
			continue
		}
		seenKey[it.Key] = true
		items = append(items, it)
		sellers[it.Seller]++
	}
	if p.Show <= 0 || len(items) == 0 {
		return nil
	}
	type scored struct {
		VarietyItem
		score float64
	}
	sc := make([]scored, len(items))
	for i, it := range items {
		q := math.Max(it.Quality, 0.01)
		if p.Deterministic {
			sc[i] = scored{it, q}
			continue
		}
		w := q / math.Sqrt(float64(max(1, sellers[it.Seller]))) // seller fairness: a seller with many items in the pool does not crowd the others
		if p.Novelty[ShortKey(it.Key)] {
			w *= math.Max(1-p.NoveltyPenalty, 0.01)
		}
		sc[i] = scored{it, math.Log(unit(p.Seed, p.Module+"\x1f"+it.Key)) / math.Max(w, 1e-6)}
	}
	sort.SliceStable(sc, func(i, j int) bool {
		if sc[i].score != sc[j].score {
			return sc[i].score > sc[j].score
		}
		return sc[i].Key < sc[j].Key
	})
	share := p.SellerShare
	if share > 0 {
		distinct := 0
		for s := range sellers {
			if s != "" {
				distinct++
			}
		}
		if distinct > 0 {
			share = max(share, (p.Show+distinct-1)/distinct)
		}
	}
	out := make([]VarietyItem, 0, p.Show)
	per := map[string]int{}
	for _, s := range sc {
		if len(out) >= p.Show {
			break
		}
		if share > 0 && s.Seller != "" && per[s.Seller] >= share {
			continue
		}
		per[s.Seller]++
		out = append(out, s.VarietyItem)
	}
	return out
}

// QualityOf scores a card 0..1 from what shoppers care about: stock depth, a real rating (a product with no reviews is
// neutral, never favoured and never shown as "0 reviews") and the seller's recency. Items reaching here already passed
// the photo verdict (eligibleCard), so photo is a gate rather than a score.
func QualityOf(p ProductSummary, ageDays int) float64 {
	stock := 0.6
	switch {
	case p.LowStock:
		stock = 0.45
	case p.AvailableQty >= 20:
		stock = 1
	case p.AvailableQty >= 5:
		stock = 0.8
	}
	rating := 0.5
	if p.ReviewCount > 0 && p.Rating > 0 {
		rating = 0.4 + 0.6*math.Min(p.Rating, 5)/5
		if p.ReviewCount < 3 {
			rating = (rating + 0.5) / 2 // a single review is weak evidence
		}
	}
	recency := 0.5 // unknown
	if ageDays > 0 {
		recency = math.Max(0.2, 1-float64(ageDays)/180)
	}
	return 0.35*stock + 0.45*rating + 0.2*recency
}

// OrderedModule is one module (or pinned staff placement) to place on the page.
type OrderedModule struct {
	Key    string
	Band   string
	Weight int
	Pinned bool // a staff placement: keeps its priority order ahead of automatic modules of its band
}

// OrderModules places modules by band (hero, personal, discovery, promo, brand). The hero and personal bands, pinned
// placements and every band for a crawler are in weight order; the other bands rotate by the seed, weighted by weight so
// a heavier module tends to come earlier but not always.
func OrderModules(in []OrderedModule, seed uint64, deterministic bool) []OrderedModule {
	out := append([]OrderedModule(nil), in...)
	rank := func(b string) int {
		if r, ok := bandRank[b]; ok {
			return r
		}
		return bandRank[BandDiscovery]
	}
	rot := func(m OrderedModule) float64 {
		if deterministic || m.Pinned || rank(m.Band) <= bandRank[BandPersonal] {
			return 0
		}
		return math.Log(unit(seed, "order\x1f"+m.Key)) / float64(m.Weight+10)
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if ra, rb := rank(a.Band), rank(b.Band); ra != rb {
			return ra < rb
		}
		if a.Pinned != b.Pinned {
			return a.Pinned
		}
		if ka, kb := rot(a), rot(b); ka != kb {
			return ka > kb
		}
		if a.Weight != b.Weight {
			return a.Weight > b.Weight
		}
		return a.Key < b.Key
	})
	return out
}

// CapSponsored keeps at most one sponsored module per `perOrganic` organic ones (default 3), earliest first; with no
// organic module no sponsored one is kept, so paid content never fills a thin page. It returns keep flags by index.
func CapSponsored(sponsored []bool, perOrganic int) []bool {
	if perOrganic < 1 {
		perOrganic = 3
	}
	organic := 0
	for _, s := range sponsored {
		if !s {
			organic++
		}
	}
	allowed, kept := organic/perOrganic, 0
	keep := make([]bool, len(sponsored))
	for i, s := range sponsored {
		if !s {
			keep[i] = true
			continue
		}
		if kept < allowed {
			keep[i] = true
			kept++
		}
	}
	return keep
}
