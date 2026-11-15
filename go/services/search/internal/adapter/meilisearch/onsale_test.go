package meilisearch

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func TestOnSaleAddsTheFlagFilterOnlyWhenAsked(t *testing.T) {
	if strings.Contains(buildFilter(domain.Query{}), "on_sale") {
		t.Fatal("an ordinary search must not filter on on_sale")
	}
	if f := buildFilter(domain.Query{OnSale: true, EligibleOnly: true}); !strings.Contains(f, "on_sale = true") || !strings.Contains(f, "in_stock = true") {
		t.Fatalf("filter %q must keep in stock and add on_sale", f)
	}
}

func TestWasPriceIsShownOnlyAboveThePriceOnAFlaggedDocument(t *testing.T) {
	if got := (hitDoc{PriceMinor: 800, ListPriceMinor: 1000, OnSale: true}).toHit().ListPriceMinor; got != 1000 {
		t.Fatalf("was price %d, want 1000", got)
	}
	for _, h := range []hitDoc{{PriceMinor: 800, ListPriceMinor: 800, OnSale: true}, {PriceMinor: 800, ListPriceMinor: 1000}, {PriceMinor: 800, ListPriceMinor: 500, OnSale: true}} {
		if got := h.toHit().ListPriceMinor; got != 0 {
			t.Fatalf("%+v shows a was price %d, want none", h, got)
		}
	}
}
