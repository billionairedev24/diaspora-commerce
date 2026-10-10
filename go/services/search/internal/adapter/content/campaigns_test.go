package content_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/content"
)

func TestCampaignsAreCachedAndServedStaleWhileContentIsDown(t *testing.T) {
	var calls atomic.Int32
	var down atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if down.Load() {
			http.Error(w, "boom", http.StatusServiceUnavailable)
			return
		}
		if r.URL.Path != "/v1/ads/serving" || r.URL.Query().Get("market") != "US" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"market":"US","campaigns":[{"id":"cmp_1","sellerOrgId":"org_1","advertiser":"Mama Tee",
			"bidMinor":40,"remainingMinor":900,"currency":"USD","keywords":["egusi"],"categories":[],"atins":["AT00000001"]}]}`))
	}))
	defer srv.Close()

	src := content.New(content.Config{BaseURL: srv.URL, CacheTTL: 50 * time.Millisecond, StaleTTL: time.Minute},
		&http.Client{Timeout: time.Second})
	ctx := context.Background()
	camps, err := src.Eligible(ctx, "us")
	if err != nil || len(camps) != 1 || camps[0].BidMinor != 40 || camps[0].Keywords[0] != "egusi" {
		t.Fatalf("campaigns = %+v %v", camps, err)
	}
	if _, err := src.Eligible(ctx, "us"); err != nil || calls.Load() != 1 {
		t.Fatalf("cached call hit content: %d", calls.Load())
	}
	time.Sleep(60 * time.Millisecond)
	down.Store(true)
	camps, err = src.Eligible(ctx, "us")
	if err != nil || len(camps) != 1 || calls.Load() != 2 {
		t.Fatalf("stale campaigns while content is down = %+v %v (%d calls)", camps, err, calls.Load())
	}
	if _, err := src.Eligible(ctx, "ca"); err == nil {
		t.Fatal("no stale entry: the error surfaces (the ad server then serves organic results only)")
	}
}
