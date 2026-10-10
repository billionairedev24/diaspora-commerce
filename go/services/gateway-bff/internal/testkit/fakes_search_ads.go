package testkit

import (
	"net/http"
	"net/url"
	"sync"
)

// TestSearchID is the searchId the fake search service hands out; TestAdToken is the click token on its ad.
const (
	TestSearchID = "srch_test_1"
	TestAdToken  = "adtok.test.1"
)

var (
	searchQueries sync.Map // *Upstreams → url.Values of the last search
	searchClicks  sync.Map // *Upstreams → map[string]any body of the last click
)

// searchSeen records the last search query (ads/track assertions) and returns its searchId.
func (u *Upstreams) searchSeen(r *http.Request) string {
	searchQueries.Store(u, r.URL.Query())
	return TestSearchID
}

// LastSearchQuery returns the query string of the last call to the fake search service.
func (u *Upstreams) LastSearchQuery() url.Values {
	if v, ok := searchQueries.Load(u); ok {
		return v.(url.Values) //nolint:forcetypeassert // only url.Values are stored
	}
	return url.Values{}
}

// LastSearchClick returns the body of the last click reported to the fake search service.
func (u *Upstreams) LastSearchClick() map[string]any {
	if v, ok := searchClicks.Load(u); ok {
		return v.(map[string]any) //nolint:forcetypeassert // only maps are stored
	}
	return nil
}

// withAds marks the first hit as sponsored when the caller asked for ads (search blends ads into slot 1).
func (u *Upstreams) withAds(r *http.Request, hits []map[string]any) []map[string]any {
	if r.URL.Query().Get("ads") != "true" || len(hits) == 0 {
		return hits
	}
	ad := map[string]any{}
	for k, v := range hits[0] {
		ad[k] = v
	}
	ad["sponsored"] = map[string]any{"label": "Sponsored", "advertiser": "Mama Tee", "campaignId": "cmp_1", "slot": 1, "token": TestAdToken,
		"reason": map[string]any{"targeting": "KEYWORD", "matched": "jollof", "qualityScore": 0.82, "auctionRank": 1, "competitors": 2,
			"eligibility": []string{"In stock", "Holds the buy box"}, "summary": "Shown because you searched for “jollof”."}}
	out := append([]map[string]any{ad}, hits[1:]...)
	return out
}

func (u *Upstreams) searchClickRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/search/clicks", func(w http.ResponseWriter, r *http.Request) {
		body := readJSON(r)
		searchClicks.Store(u, body)
		tok, _ := body["adToken"].(string)
		res := map[string]any{"recorded": true, "duplicate": false, "sponsored": tok != ""}
		if tok == TestAdToken {
			res["adStatus"] = "BILLABLE"
		} else if tok != "" {
			res["adStatus"] = "EXPIRED"
		}
		writeJSON(w, 202, res)
	})
}
