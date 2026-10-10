package domain_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func cand(id string, bid, remaining int64, quality float64, atin string) domain.AdCandidate {
	return domain.AdCandidate{Campaign: domain.Campaign{ID: id, BidMinor: bid, RemainingMinor: remaining},
		Hit: domain.Hit{ATIN: atin}, Quality: quality}
}

func TestSecondPriceAuctionWithRelevanceFloor(t *testing.T) {
	rules := domain.AuctionRules{MinQuality: 0.3, ReserveMinor: 5, IncrementMinor: 1}
	winners := domain.RunAuction([]domain.AdCandidate{
		cand("a", 100, 1000, 0.5, "AT0000000A"), // rank 50
		cand("b", 60, 1000, 1.0, "AT0000000B"),  // rank 60
		cand("c", 40, 1000, 0.5, "AT0000000C"),  // rank 20
		cand("d", 900, 1000, 0.2, "AT0000000D"), // below the relevance floor: a big bid cannot buy irrelevance
		cand("e", 80, 3, 1.0, "AT0000000E"),     // budget below the reserve
		cand("b", 70, 1000, 0.9, "AT0000000F"),  // same campaign again: only its best ad
	}, 2, rules)
	if len(winners) != 2 {
		t.Fatalf("winners = %+v", winners)
	}
	if winners[0].Candidate.Campaign.ID != "b" || winners[0].Candidate.Hit.ATIN != "AT0000000F" || winners[0].Rank != 1 {
		t.Fatalf("first = %+v", winners[0])
	}
	// b(F) pays the smallest bid keeping it above a: ceil(50 / 0.9) + 1 = 57 ≤ its bid 70.
	if winners[0].PriceMinor != 57 {
		t.Fatalf("first price = %d", winners[0].PriceMinor)
	}
	// a pays ceil(20 / 0.5) + 1 = 41 (runner-up c), not its bid of 100.
	if winners[1].Candidate.Campaign.ID != "a" || winners[1].PriceMinor != 41 || winners[1].Competitors != 3 {
		t.Fatalf("second = %+v", winners[1])
	}
}

func TestAuctionPriceBounds(t *testing.T) {
	rules := domain.AuctionRules{MinQuality: 0.1, ReserveMinor: 5, IncrementMinor: 1}
	alone := domain.RunAuction([]domain.AdCandidate{cand("a", 50, 100, 0.8, "AT0000000A")}, 2, rules)
	if len(alone) != 1 || alone[0].PriceMinor != 5 {
		t.Fatalf("a lone ad pays the reserve: %+v", alone)
	}
	tight := domain.RunAuction([]domain.AdCandidate{
		cand("a", 50, 100, 0.5, "AT0000000A"), cand("b", 49, 100, 0.5, "AT0000000B"),
	}, 1, rules)
	if tight[0].PriceMinor != 50 {
		t.Fatalf("the price never exceeds the bid: %+v", tight[0])
	}
}

func TestMatchCampaign(t *testing.T) {
	c := domain.Campaign{Keywords: []string{"Egusi", "melon seeds", "egusi soup mix"}, Categories: []string{"Groceries › Pantry"}}
	cases := []struct {
		query string
		cats  []string
		kind  string
		value string
		ok    bool
	}{
		{"egusi", nil, domain.TargetKeyword, "egusi", true},
		{"ground egusi 1 lb", nil, domain.TargetKeyword, "egusi", true},
		{"melon seed", nil, domain.TargetKeyword, "melon seeds", true},
		{"egusi soup mix", nil, domain.TargetKeyword, "egusi soup mix", true},
		{"shea butter", []string{"Groceries › Pantry › Oils"}, domain.TargetCategory, "Groceries › Pantry", true},
		{"shea butter", []string{"Groceries › Pantryish"}, "", "", false},
		{"shea butter", []string{"Beauty › Skin care"}, "", "", false},
	}
	for _, tc := range cases {
		m, ok := domain.MatchCampaign(c, tc.query, tc.cats)
		if ok != tc.ok || m.Kind != tc.kind || m.Value != tc.value {
			t.Fatalf("%q: %+v %v", tc.query, m, ok)
		}
	}
}

func TestExplainNeverRevealsBids(t *testing.T) {
	w := domain.AuctionWinner{Rank: 1, Competitors: 2, PriceMinor: 35, Candidate: domain.AdCandidate{
		Campaign: domain.Campaign{ID: "cmp", Advertiser: "Mama Tee", BidMinor: 40},
		Hit:      domain.Hit{Title: "Ground Egusi"}, Match: domain.TargetMatch{Kind: domain.TargetKeyword, Value: "egusi"},
		Quality: 0.876,
	}}
	r := domain.Explain(w, "egusi")
	if r.QualityScore != 0.88 || len(r.Eligibility) == 0 || r.Summary == "" {
		t.Fatalf("reason: %+v", r)
	}
	for _, s := range []string{"40", "35"} {
		if strings.Contains(r.Summary, s) {
			t.Fatalf("summary leaks money: %s", r.Summary)
		}
	}
}

func TestSynonymRuleNormalize(t *testing.T) {
	r, err := domain.SynonymRule{ID: "Plantain-Chips", Synonyms: " Plantain  Chips, kpekere,KPEKERE , ipekere"}.Normalize()
	if err != nil || r.ID != "plantain-chips" || r.Synonyms != "plantain chips, kpekere, ipekere" {
		t.Fatalf("rule = %+v %v", r, err)
	}
	for _, bad := range []domain.SynonymRule{
		{ID: "x y", Synonyms: "a, b"}, {ID: "one", Synonyms: "a"}, {ID: "map", Synonyms: "a => b"},
	} {
		if _, err := bad.Normalize(); !errors.Is(err, domain.ErrInvalidSynonym) {
			t.Fatalf("%+v: %v", bad, err)
		}
	}
}

func TestAnalyticsKey(t *testing.T) {
	if k := domain.AnalyticsKey("  Jollof RICE,  seasoning!! "); k != "jollof rice seasoning" {
		t.Fatalf("key = %q", k)
	}
}
