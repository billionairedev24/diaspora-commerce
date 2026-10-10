package domain

import "sort"

// Availability is the stock signal of a product card (S038).
type Availability string

// Availability values. The storefront shows "Only N left" for LOW_STOCK and "Currently unavailable" for OUT_OF_STOCK.
const (
	AvailabilityInStock    Availability = "IN_STOCK"
	AvailabilityLowStock   Availability = "LOW_STOCK"
	AvailabilityOutOfStock Availability = "OUT_OF_STOCK"
)

// StockSignal returns the card's availability and the low-stock flag: low stock is 1–LowStockThreshold units
// available; zero (or no stock record) is out of stock.
func StockSignal(inStock bool, available int) (Availability, bool) {
	switch {
	case !inStock || available <= 0:
		return AvailabilityOutOfStock, false
	case available <= LowStockThreshold:
		return AvailabilityLowStock, true
	default:
		return AvailabilityInStock, false
	}
}

// ApplyStockSignal sets Availability and LowStock on a hit from its quantity.
func (h *Hit) ApplyStockSignal() {
	h.Availability, h.LowStock = StockSignal(h.InStock, h.AvailableQty)
}

// Retrieval says how a results page was retrieved (S036).
type Retrieval string

// Retrieval modes.
const (
	// RetrievalHybrid: BM25 and kNN over the embedding, fused by reciprocal rank.
	RetrievalHybrid Retrieval = "HYBRID"
	// RetrievalLexical: BM25 only (SemanticSkipped says why).
	RetrievalLexical Retrieval = "LEXICAL"
)

// Why semantic retrieval was not used for a search.
const (
	SkipShortQuery    = "SHORT_QUERY"          // fewer words than the semantic minimum (3)
	SkipNotConfigured = "NOT_CONFIGURED"       // no AI gateway configured
	SkipSorted        = "SORTED"               // an explicit sort (price, rating, newest) replaces relevance
	SkipDeepPage      = "DEEP_PAGE"            // beyond the fused window
	SkipUnavailable   = "EMBEDDER_UNAVAILABLE" // the AI gateway failed
	SkipTimeout       = "EMBEDDER_TIMEOUT"     // no embedding within the budget (150 ms)
)

// RRFK is the rank constant of reciprocal rank fusion (the value from Cormack et al., also Elasticsearch's default).
const RRFK = 60

// HybridWindow is how many results of each retriever are fused; pages past it are lexical only.
const HybridWindow = 120

// Fused is one document after reciprocal rank fusion.
type Fused struct {
	ID    string
	Score float64
	// Lexical and Semantic are 1-based ranks in each list (0 = not in that list).
	Lexical, Semantic int
}

// FuseRRF merges ranked lists of ids by reciprocal rank fusion: score = Σ 1 / (k + rank). Ties keep the lexical
// order first, then the semantic order.
func FuseRRF(lexical, semantic []string, k int) []Fused {
	if k <= 0 {
		k = RRFK
	}
	byID := map[string]*Fused{}
	order := []string{}
	add := func(ids []string, lex bool) {
		for i, id := range ids {
			f, ok := byID[id]
			if !ok {
				f = &Fused{ID: id}
				byID[id] = f
				order = append(order, id)
			}
			if (lex && f.Lexical != 0) || (!lex && f.Semantic != 0) {
				continue // duplicate within a list: first rank wins
			}
			f.Score += 1 / float64(k+i+1)
			if lex {
				f.Lexical = i + 1
			} else {
				f.Semantic = i + 1
			}
		}
	}
	add(lexical, true)
	add(semantic, false)
	out := make([]Fused, 0, len(order))
	for _, id := range order {
		out = append(out, *byID[id])
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}
