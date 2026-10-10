package catalog_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/catalog"
)

func TestOffersMapRetailAndCache(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch r.URL.Path {
		case "/v1/public/variants/ATJOL00001":
			_, _ = w.Write([]byte(`{"product":{"atin":"ATP0000001","ownerKind":"PLATFORM","sellerOrgId":"org_retail",
				"title":"Jollof Seasoning","images":[{"url":"https://cdn/j.jpg"}]},
				"variant":{"atin":"ATJOL00001","options":{"Net weight":"8 oz"},"priceMinor":899,"currency":"usd"}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	r := catalog.New(srv.URL, srv.Client(), time.Minute, "org_retail", nil)
	out, err := r.Offers(context.Background(), []string{"ATJOL00001", "ATGONE0001"})
	if err != nil {
		t.Fatal(err)
	}
	o, ok := out["ATJOL00001"]
	if !ok || o.Fulfilment != "AFROTRANSACT" || o.SellerName != "AfroTransact Retail" || o.Currency != "USD" ||
		o.ImageURL != "https://cdn/j.jpg" {
		t.Fatalf("offer %+v", o)
	}
	if _, ok := out["ATGONE0001"]; ok {
		t.Fatal("non-live variant must be absent")
	}
	_, _ = r.Offers(context.Background(), []string{"ATJOL00001", "ATGONE0001"})
	if calls.Load() != 2 {
		t.Fatalf("second read should be cached, calls=%d", calls.Load())
	}
}
