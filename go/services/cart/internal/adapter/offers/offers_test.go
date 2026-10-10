package offers_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/offers"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

func TestOfferLookup(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		switch r.URL.Path {
		case "/v1/public/offers/o-1":
			_, _ = w.Write([]byte(`{"offer":{"offerId":"o-1","atin":"ATJOL00001","productAtin":"ATJOL00000",
				"seller":{"id":"org_lagos","name":"Lagos Textile Hub","kind":"SELLER"},"priceMinor":799,"currency":"usd",
				"fulfilment":"SELLER","availableQty":4},"buyable":true}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	l := offers.New(srv.URL, srv.Client(), time.Minute)
	o, err := l.Offer(context.Background(), "o-1")
	if err != nil {
		t.Fatal(err)
	}
	if !o.Buyable || o.VariantATIN != "ATJOL00001" || o.PriceMinor != 799 || o.Currency != "USD" || o.Available != 4 || o.SellerName != "Lagos Textile Hub" {
		t.Fatalf("got %+v", o)
	}
	if _, err := l.Offer(context.Background(), "o-1"); err != nil || calls != 1 {
		t.Fatalf("expected cache hit, calls=%d err=%v", calls, err)
	}
	if _, err := l.Offer(context.Background(), "gone"); !errors.Is(err, port.ErrOfferNotFound) {
		t.Fatalf("expected ErrOfferNotFound, got %v", err)
	}
}
