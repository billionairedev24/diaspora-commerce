package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func organicPage() domain.Page {
	hits := []domain.Hit{}
	for _, a := range []string{"AT0000000A", "AT0000000B", "AT0000000C", "AT0000000D", "AT0000000E", "AT0000000F"} {
		hits = append(hits, domain.Hit{ATIN: a, Title: "Egusi " + a, Score: 10, InStock: true, AvailableQty: 20,
			CategoryPath: "Groceries › Pantry › Seeds & nuts", Department: "Groceries", SellerID: "org_other"})
	}
	return domain.Page{Hits: hits, Total: 6, SearchID: "srch-0001-test"}
}

var settings = app.AdSettings{Slots: []int{1, 5}, TokenTTL: time.Hour,
	Rules: domain.AuctionRules{MinQuality: 0.3, ReserveMinor: 5, IncrementMinor: 1}}

func TestSponsoredProductsFillFixedSlotsWithReasons(t *testing.T) {
	idx := &fakeIndex{adHits: []domain.Hit{
		{ATIN: "AT00000AD1", Title: "Ground Egusi", Score: 9, InStock: true, AvailableQty: 3, SellerID: "org_mama",
			VariantATINs: []string{"AT00000AD1"}},
		{ATIN: "AT00000AD2", Title: "Agushi Seeds", Score: 6, InStock: true, SellerID: "org_accra"},
		{ATIN: "AT0000000C", Title: "Egusi C", Score: 10, InStock: true, SellerID: "org_other"},
		{ATIN: "AT00000AD4", Title: "Barely relevant", Score: 1, InStock: true, SellerID: "org_low"},
		{ATIN: "AT00000AD5", Title: "Someone else's buy box", Score: 10, InStock: true, SellerID: "org_buybox"},
	}}
	camps := fakeCampaigns{camps: []domain.Campaign{
		{ID: "cmp_mama", SellerOrgID: "org_mama", Advertiser: "Mama Tee", BidMinor: 40, RemainingMinor: 1000,
			Currency: "usd", Keywords: []string{"egusi"}, ATINs: []string{"AT00000AD1"}},
		{ID: "cmp_accra", SellerOrgID: "org_accra", Advertiser: "Accra Pantry", BidMinor: 50, RemainingMinor: 1000,
			Keywords: []string{"melon seeds"}, Categories: []string{"Groceries › Pantry"}, ATINs: []string{"AT00000AD2"}},
		{ID: "cmp_other", SellerOrgID: "org_other", Advertiser: "Other", BidMinor: 10, RemainingMinor: 1000,
			Keywords: []string{"egusi"}, ATINs: []string{"AT0000000C"}},
		{ID: "cmp_low", SellerOrgID: "org_low", Advertiser: "Low", BidMinor: 500, RemainingMinor: 1000,
			Keywords: []string{"egusi"}, ATINs: []string{"AT00000AD4"}},
		{ID: "cmp_reseller", SellerOrgID: "org_reseller", Advertiser: "Reseller", BidMinor: 500, RemainingMinor: 1000,
			Keywords: []string{"egusi"}, ATINs: []string{"AT00000AD5"}},
		{ID: "cmp_broke", SellerOrgID: "org_mama", Advertiser: "Mama Tee", BidMinor: 900, RemainingMinor: 0,
			Keywords: []string{"egusi"}, ATINs: []string{"AT00000AD1"}},
		{ID: "cmp_offtopic", SellerOrgID: "org_mama", Advertiser: "Mama Tee", BidMinor: 900, RemainingMinor: 900,
			Keywords: []string{"shea butter"}, ATINs: []string{"AT00000AD1"}},
	}}
	bus := &fakeBus{}
	ads := app.NewAdServer(camps, idx, fakeTokens{}, bus, settings, quiet)
	q, _ := domain.Query{Text: "egusi", Ads: true}.Normalize()
	page := ads.Inject(context.Background(), q, organicPage())

	if !strings.Contains(strings.Join(idx.adATINs, ","), "AD5") {
		t.Fatalf("keyword-matched products are scored: %v", idx.adATINs)
	}
	// Ranks: mama 40×0.9=36, accra 50×0.6=30 (category match), other 10×1=10; low under the relevance floor,
	// reseller without the buy box, broke without budget, offtopic not targeted.
	first, fifth := page.Hits[0], page.Hits[4]
	if first.ATIN != "AT00000AD1" || first.Sponsored == nil || first.Sponsored.Label != "Sponsored" ||
		first.Sponsored.Slot != 1 || first.Sponsored.Advertiser != "Mama Tee" || !first.LowStock {
		t.Fatalf("slot 1: %+v %+v", first, first.Sponsored)
	}
	r := first.Sponsored.Reason
	if r.Targeting != domain.TargetKeyword || r.Matched != "egusi" || r.AuctionRank != 1 || r.Competitors != 3 ||
		r.QualityScore != 0.9 || !strings.Contains(r.Summary, "ranked 1 of 3") {
		t.Fatalf("reason: %+v", r)
	}
	if fifth.ATIN != "AT00000AD2" || fifth.Sponsored == nil || fifth.Sponsored.Slot != 5 ||
		fifth.Sponsored.Reason.Targeting != domain.TargetCategory {
		t.Fatalf("slot 5: %+v", fifth)
	}
	// Second price: mama pays ceil(30 / 0.9) + 1 = 35 (< bid 40); accra pays ceil(10 / 0.6) + 1 = 18.
	tok, _ := fakeTokens{}.Verify(first.Sponsored.Token)
	if tok.PriceMinor != 35 || tok.CampaignID != "cmp_mama" || tok.SearchID != "srch-0001-test" {
		t.Fatalf("token: %+v", tok)
	}
	tok5, _ := fakeTokens{}.Verify(fifth.Sponsored.Token)
	if tok5.PriceMinor != 18 {
		t.Fatalf("slot 5 price: %d", tok5.PriceMinor)
	}
	if len(page.Hits) != 8 {
		t.Fatalf("6 organic + 2 ads, got %d", len(page.Hits))
	}
	imps := bus.ofType(domain.EventAdImpression)
	if len(imps) != 2 || imps[0].Topic() != "at.ads.events" || imps[0].AggregateID != "cmp_mama" {
		t.Fatalf("impressions: %+v", imps)
	}
	// Same search, same ad: same impression id (consumers de-duplicate on it).
	bus2 := &fakeBus{}
	app.NewAdServer(camps, idx, fakeTokens{}, bus2, settings, quiet).Inject(context.Background(), q, organicPage())
	if bus2.ofType(domain.EventAdImpression)[0].ID != imps[0].ID {
		t.Fatal("impression ids must be stable per search and ad")
	}
}

func TestAdvertisedOrganicResultIsNotShownTwice(t *testing.T) {
	idx := &fakeIndex{adHits: []domain.Hit{{ATIN: "AT0000000D", Title: "Egusi D", Score: 10, InStock: true, SellerID: "org_other"}}}
	camps := fakeCampaigns{camps: []domain.Campaign{{ID: "cmp_d", SellerOrgID: "org_other", Advertiser: "Other",
		BidMinor: 20, RemainingMinor: 100, Keywords: []string{"egusi"}, ATINs: []string{"AT0000000D"}}}}
	page := app.NewAdServer(camps, idx, fakeTokens{}, &fakeBus{}, settings, quiet).
		Inject(context.Background(), domain.Query{Text: "egusi", Market: "us"}, organicPage())
	count := 0
	for _, h := range page.Hits {
		if h.ATIN == "AT0000000D" {
			count++
		}
	}
	if count != 1 || page.Hits[0].ATIN != "AT0000000D" || len(page.Hits) != 6 {
		t.Fatalf("advertised product shown %d times: %+v", count, page.Hits)
	}
}

func TestAdsFailOpen(t *testing.T) {
	bus := &fakeBus{}
	ads := app.NewAdServer(fakeCampaigns{err: errors.New("content down")}, &fakeIndex{}, fakeTokens{}, bus, settings, quiet)
	page := ads.Inject(context.Background(), domain.Query{Text: "egusi", Market: "us"}, organicPage())
	if len(page.Hits) != 6 || page.Hits[0].Sponsored != nil || len(bus.events) != 0 {
		t.Fatal("content outage must leave organic results untouched")
	}
	empty := ads.Inject(context.Background(), domain.Query{Text: "egusi", Market: "us"}, domain.Page{})
	if len(empty.Hits) != 0 {
		t.Fatal("no ads on zero-result pages")
	}
}
