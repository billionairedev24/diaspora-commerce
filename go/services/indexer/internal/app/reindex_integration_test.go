package app_test

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/meilisearch"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sources"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

// These tests run the real reindexer, sources and projector against a real Meilisearch (the HTTP sources are
// httptest servers): MEILISEARCH_TEST_URL=http://127.0.0.1:7700 (MEILISEARCH_TEST_KEY).
func meiliSetup(t *testing.T) *meili.Client {
	t.Helper()
	url := os.Getenv("MEILISEARCH_TEST_URL")
	if url == "" {
		t.Skip("set MEILISEARCH_TEST_URL to run against Meilisearch")
	}
	return meili.New(url, os.Getenv("MEILISEARCH_TEST_KEY"), &http.Client{Timeout: 20 * time.Second})
}

func newMarket(t *testing.T, c *meili.Client) string {
	t.Helper()
	market := fmt.Sprintf("r%d", rand.IntN(1_000_000))
	if err := meilisearch.NewIndexAdmin(c).EnsureIndex(t.Context(), market); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = c.Task(context.WithoutCancel(t.Context()), http.MethodDelete, "/indexes/"+searchindex.MeiliIndexUID(market), nil)
	})
	return market
}

// volatile fields differ between two writes of the same state: write times and the event / snapshot versions.
var volatile = []string{"updated_at", "updated_ts", "version", "variant_qty_version", "rating_version"}

func stable(t *testing.T, c *meili.Client, market, atin string) map[string]any {
	t.Helper()
	d, err := c.Get(t.Context(), searchindex.MeiliIndexUID(market), atin)
	if err != nil || d == nil {
		t.Fatalf("get %s in %s: %v %v", atin, market, d, err)
	}
	for _, k := range volatile {
		delete(d, k)
	}
	return d
}

func exportServers(t *testing.T, item domain.ProductExportItem) (catalogURL, offersURL, inventoryURL string) {
	t.Helper()
	cat := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(domain.ProductExportPage{Items: []domain.ProductExportItem{item}})
	}))
	offers := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprint(w, `{"items":[{"atin":"AT0000000A","buyBox":{"offerId":"o1","availableQty":7}},{"atin":"AT0000000B","buyBox":null}]}`)
	}))
	inv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprint(w, `[{"sku":"AT0000000A","nodes":[{"nodeId":"dc1","available":9,"version":4}]}]`)
	}))
	t.Cleanup(func() { cat.Close(); offers.Close(); inv.Close() })
	return cat.URL, offers.URL, inv.URL
}

func TestReindexEqualsTheEventPathOnMeilisearch(t *testing.T) {
	c := meiliSetup(t)
	item := exportItem("AT00000001", "AT0000000A", "AT0000000B")
	item.Product.Description = "Smoky party jollof seasoning."
	item.Product.Keywords = []string{"party rice"}
	item.Product.Communities = []string{"Nigerian"}
	item.Rating = &domain.ProductExportRating{Average: 4.5, Count: 12, UpdatedAt: reindexNow.Add(-time.Hour)}
	catalogURL, offersURL, inventoryURL := exportServers(t, item)

	// The event path, market A.
	eventMarket := newMarket(t, c)
	live := app.NewProjector(meilisearch.NewIndex(c), fakeCatalog{}, fakeSellers{}, eventMarket, quiet)
	send := func(typ string, at time.Time, payload any) {
		t.Helper()
		raw, _ := json.Marshal(payload)
		if err := live.Handle(t.Context(), events.Envelope{ID: typ, Type: typ, OccurredAt: at, Payload: raw}); err != nil {
			t.Fatal(err)
		}
	}
	send(domain.ProductApproved, item.Version, item.Product)
	send(domain.RatingChanged, item.Rating.UpdatedAt, domain.RatingEvent{ATIN: "AT00000001", Average: 4.5, Count: 12})
	winner, qty := "o1", 7
	send(domain.BuyBoxChanged, reindexNow, domain.BuyBoxEvent{ATIN: "AT0000000A", WinnerOfferID: &winner, AvailableQty: &qty, DecidedAt: reindexNow})
	send(domain.BuyBoxChanged, reindexNow, domain.BuyBoxEvent{ATIN: "AT0000000B", DecidedAt: reindexNow})
	send(domain.AvailabilityChanged, reindexNow, domain.AvailabilityEvent{SKU: "AT0000000A", Node: "dc1", Available: 9, Version: 4})

	// The reindex path, market B, from the same state.
	reindexMarket := newMarket(t, c)
	idx := meilisearch.NewIndex(c)
	reindexer := app.NewReindexer(app.ReindexDeps{
		Products: sources.NewCatalog(sources.CatalogConfig{BaseURL: catalogURL}, http.DefaultClient),
		BuyBoxes: sources.NewOffers(offersURL, http.DefaultClient),
		Stock:    sources.NewInventory(inventoryURL, http.DefaultClient),
		EnsureIndex: func(ctx context.Context, m string) error {
			return meilisearch.NewIndexAdmin(c).EnsureIndex(ctx, m)
		},
		Handlers: map[string]events.Handler{reindexMarket: app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, reindexMarket, quiet)},
		Log:      quiet,
	})
	for run := 1; run <= 2; run++ { // the second run must change nothing
		sum, err := reindexer.Run(t.Context(), app.ReindexOptions{Markets: []string{reindexMarket}})
		if err != nil || sum.Failures() != 0 || sum.Markets[0].Written != 1 {
			t.Fatalf("run %d: err = %v, summary = %+v", run, err, sum)
		}
		want, got := stable(t, c, eventMarket, "AT00000001"), stable(t, c, reindexMarket, "AT00000001")
		if !reflect.DeepEqual(want, got) {
			t.Fatalf("run %d: reindexed document differs from the event path's:\n event   %v\n reindex %v", run, want, got)
		}
	}
}

func TestReindexNeverOverwritesANewerLiveDocument(t *testing.T) {
	c := meiliSetup(t)
	item := exportItem("AT00000001", "AT0000000A", "AT0000000B")
	catalogURL, offersURL, inventoryURL := exportServers(t, item)
	market := newMarket(t, c)
	idx := meilisearch.NewIndex(c)
	projector := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, market, quiet)

	// The live indexer already wrote a newer title than the export knows about.
	newer := item.Product
	newer.Title = "Renamed after the export"
	raw, _ := json.Marshal(newer)
	if err := projector.Handle(t.Context(), events.Envelope{ID: "live", Type: domain.ProductApproved, OccurredAt: item.Version.Add(time.Hour), Payload: raw}); err != nil {
		t.Fatal(err)
	}

	reindexer := app.NewReindexer(app.ReindexDeps{
		Products: sources.NewCatalog(sources.CatalogConfig{BaseURL: catalogURL}, http.DefaultClient),
		BuyBoxes: sources.NewOffers(offersURL, http.DefaultClient),
		Stock:    sources.NewInventory(inventoryURL, http.DefaultClient),
		EnsureIndex: func(ctx context.Context, m string) error {
			return meilisearch.NewIndexAdmin(c).EnsureIndex(ctx, m)
		},
		Handlers: map[string]events.Handler{market: projector},
		Log:      quiet,
	})
	if _, err := reindexer.Run(t.Context(), app.ReindexOptions{Markets: []string{market}}); err != nil {
		t.Fatal(err)
	}
	if got := stable(t, c, market, "AT00000001")["title"]; got != "Renamed after the export" {
		t.Fatalf("title = %v, the older export overwrote the live document", got)
	}
}
