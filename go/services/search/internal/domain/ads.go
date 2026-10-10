package domain

import (
	"fmt"
	"math"
	"sort"
	"strings"
)

// SponsoredLabel is shown on every ad (design: "Sponsored · {advertiser}").
const SponsoredLabel = "Sponsored"

// Targeting kinds.
const (
	TargetKeyword  = "KEYWORD"
	TargetCategory = "CATEGORY"
)

// Campaign is a sponsored-products campaign eligible to serve, as published by content-service: active, inside its
// schedule and with budget left today.
type Campaign struct {
	ID             string   `json:"id"`
	SellerOrgID    string   `json:"sellerOrgId"`
	Advertiser     string   `json:"advertiser"`
	BidMinor       int64    `json:"bidMinor"`
	RemainingMinor int64    `json:"remainingMinor"`
	Currency       string   `json:"currency"`
	Keywords       []string `json:"keywords"`
	Categories     []string `json:"categories"`
	ATINs          []string `json:"atins"`
}

// TargetMatch is why a campaign entered the auction for a query.
type TargetMatch struct {
	Kind  string `json:"kind"`
	Value string `json:"value"`
}

// MatchCampaign returns the campaign's targeting that matches the shopper's query: a keyword whose words all appear in
// the query (exact phrase first), else a category equal to — or a prefix of — the department filter or the category
// of a top organic result.
func MatchCampaign(c Campaign, query string, categories []string) (TargetMatch, bool) {
	key := AnalyticsKey(query)
	if key != "" {
		words := wordSet(key)
		best := ""
		for _, kw := range c.Keywords {
			k := AnalyticsKey(kw)
			if k == "" {
				continue
			}
			if k == key {
				return TargetMatch{Kind: TargetKeyword, Value: k}, true
			}
			if containsAll(words, strings.Fields(k)) && len(k) > len(best) {
				best = k
			}
		}
		if best != "" {
			return TargetMatch{Kind: TargetKeyword, Value: best}, true
		}
	}
	for _, want := range c.Categories {
		w := strings.ToLower(strings.TrimSpace(want))
		if w == "" {
			continue
		}
		for _, have := range categories {
			h := strings.ToLower(have)
			if h == w || strings.HasPrefix(h, w+" › ") {
				return TargetMatch{Kind: TargetCategory, Value: want}, true
			}
		}
	}
	return TargetMatch{}, false
}

func wordSet(s string) map[string]bool {
	out := map[string]bool{}
	for _, w := range strings.Fields(s) {
		out[w] = true
		out[strings.TrimSuffix(w, "s")] = true
	}
	return out
}

func containsAll(set map[string]bool, words []string) bool {
	for _, w := range words {
		if !set[w] && !set[strings.TrimSuffix(w, "s")] {
			return false
		}
	}
	return len(words) > 0
}

// AdCandidate is one eligible ad: the campaign, the advertised product's buy-box document and its relevance.
type AdCandidate struct {
	Campaign Campaign
	Hit      Hit
	Match    TargetMatch
	// Quality is the product's relevance to the query relative to the best organic result (0–1).
	Quality float64
}

// Rank is the auction score: bid × quality.
func (c AdCandidate) Rank() float64 { return float64(c.Campaign.BidMinor) * c.Quality }

// AuctionRules are the marketplace's published ad rules.
type AuctionRules struct {
	// MinQuality is the relevance floor: ads less relevant than this share of the best organic result never show.
	MinQuality float64
	// ReserveMinor is the minimum cost per click.
	ReserveMinor int64
	// IncrementMinor is added to the runner-up's rank in the second-price formula.
	IncrementMinor int64
}

// AuctionWinner is an ad that won a slot, with the cost per click it would pay (second price).
type AuctionWinner struct {
	Candidate   AdCandidate
	Rank        int // 1-based auction rank
	PriceMinor  int64
	Competitors int // eligible ads in the auction
}

// RunAuction is a generalised second-price auction on bid × quality: candidates below the relevance floor, without
// budget for the reserve price or duplicating a campaign or product already placed are dropped; the winner of rank i
// pays the smallest bid that keeps its rank — ceil(rank(i+1) / quality(i)) + increment — at least the reserve and at
// most its own bid.
func RunAuction(cands []AdCandidate, slots int, rules AuctionRules) []AuctionWinner {
	eligible := make([]AdCandidate, 0, len(cands))
	for _, c := range cands {
		if c.Quality < rules.MinQuality || c.Quality <= 0 || c.Campaign.BidMinor < rules.ReserveMinor ||
			c.Campaign.RemainingMinor < rules.ReserveMinor {
			continue
		}
		eligible = append(eligible, c)
	}
	sort.SliceStable(eligible, func(i, j int) bool {
		ri, rj := eligible[i].Rank(), eligible[j].Rank()
		if ri != rj {
			return ri > rj
		}
		if eligible[i].Campaign.ID != eligible[j].Campaign.ID {
			return eligible[i].Campaign.ID < eligible[j].Campaign.ID
		}
		return eligible[i].Hit.ATIN < eligible[j].Hit.ATIN
	})
	// One ad per campaign and per product: keep each one's best placement.
	seenCampaign, seenATIN := map[string]bool{}, map[string]bool{}
	ranked := make([]AdCandidate, 0, len(eligible))
	for _, c := range eligible {
		if seenCampaign[c.Campaign.ID] || seenATIN[c.Hit.ATIN] {
			continue
		}
		seenCampaign[c.Campaign.ID], seenATIN[c.Hit.ATIN] = true, true
		ranked = append(ranked, c)
	}
	winners := make([]AuctionWinner, 0, slots)
	for i := 0; i < len(ranked) && len(winners) < slots; i++ {
		c := ranked[i]
		price := rules.ReserveMinor
		if i+1 < len(ranked) {
			next := int64(math.Ceil(ranked[i+1].Rank()/c.Quality)) + rules.IncrementMinor
			price = max(price, next)
		}
		price = min(price, c.Campaign.BidMinor)
		winners = append(winners, AuctionWinner{Candidate: c, Rank: i + 1, PriceMinor: price, Competitors: len(ranked)})
	}
	return winners
}

// AdReason explains why an ad was shown (fairness and transparency; never other advertisers' bids).
type AdReason struct {
	Targeting    string   `json:"targeting"`
	Matched      string   `json:"matched"`
	QualityScore float64  `json:"qualityScore"`
	AuctionRank  int      `json:"auctionRank"`
	Competitors  int      `json:"competitors"`
	Eligibility  []string `json:"eligibility"`
	Summary      string   `json:"summary"`
}

// Sponsored marks an ad in the results.
type Sponsored struct {
	Label      string   `json:"label"`
	Advertiser string   `json:"advertiser"`
	CampaignID string   `json:"campaignId"`
	Slot       int      `json:"slot"`
	Token      string   `json:"token"`
	Reason     AdReason `json:"reason"`
}

// AdEligibility lists the eligibility rules every served ad met.
var AdEligibility = []string{
	"campaign active with budget left",
	"in stock",
	"buy-box offer sold by the advertiser",
	"seller visible",
	"relevance above the floor",
}

// Explain builds the reason shown with an ad.
func Explain(w AuctionWinner, query string) AdReason {
	c := w.Candidate
	var why string
	if c.Match.Kind == TargetKeyword {
		why = fmt.Sprintf("matches your search %q (keyword %q)", query, c.Match.Value)
	} else {
		why = fmt.Sprintf("is in %s, the category you are browsing", c.Match.Value)
	}
	q := math.Round(c.Quality*100) / 100
	return AdReason{
		Targeting: c.Match.Kind, Matched: c.Match.Value, QualityScore: q, AuctionRank: w.Rank, Competitors: w.Competitors,
		Eligibility: AdEligibility,
		Summary: fmt.Sprintf("Sponsored by %s: %s %s, is in stock and sold by the advertiser; ranked %d of %d eligible "+
			"ads on bid × relevance (relevance %.2f).", c.Campaign.Advertiser, c.Hit.Title, why, w.Rank, w.Competitors, q),
	}
}

// AdImpression is the payload of ads.impression.recorded.
type AdImpression struct {
	ImpressionID string  `json:"impressionId"`
	CampaignID   string  `json:"campaignId"`
	SellerOrgID  string  `json:"sellerOrgId"`
	ATIN         string  `json:"atin"`
	SearchID     string  `json:"searchId"`
	Market       string  `json:"market"`
	Query        string  `json:"query"`
	Slot         int     `json:"slot"`
	AuctionRank  int     `json:"auctionRank"`
	QualityScore float64 `json:"qualityScore"`
	OccurredAt   string  `json:"occurredAt"`
}

// AdClick is the payload of ads.click.recorded.
type AdClick struct {
	ClickID      string   `json:"clickId"`
	CampaignID   string   `json:"campaignId"`
	SellerOrgID  string   `json:"sellerOrgId"`
	ATIN         string   `json:"atin"`
	VariantATINs []string `json:"variantAtins"`
	SearchID     string   `json:"searchId"`
	Market       string   `json:"market"`
	Query        string   `json:"query"`
	Slot         int      `json:"slot"`
	PriceMinor   int64    `json:"priceMinor"`
	Currency     string   `json:"currency"`
	CustomerID   string   `json:"customerId,omitempty"`
	OccurredAt   string   `json:"occurredAt"`
}

// AdToken is what a served ad's opaque click token carries (signed by search; verified on click).
type AdToken struct {
	Version      int      `json:"v"`
	SearchID     string   `json:"s"`
	CampaignID   string   `json:"c"`
	SellerOrgID  string   `json:"o"`
	ATIN         string   `json:"a"`
	VariantATINs []string `json:"va,omitempty"`
	PriceMinor   int64    `json:"p"`
	Currency     string   `json:"cu"`
	Market       string   `json:"m"`
	Query        string   `json:"q"`
	Slot         int      `json:"sl"`
	ExpiresAt    int64    `json:"e"`
}
