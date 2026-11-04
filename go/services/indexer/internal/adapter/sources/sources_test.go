package sources_test

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sources"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

func serve(t *testing.T, h http.HandlerFunc) (string, *http.Client) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv.URL, srv.Client()
}

func TestCatalogRequestsTheExportPageAndDecodesIt(t *testing.T) {
	var got url.Values
	var path string
	base, hc := serve(t, func(w http.ResponseWriter, r *http.Request) {
		got, path = r.URL.Query(), r.URL.Path
		_, _ = fmt.Fprint(w, `{"items":[{"product":{"productId":"p1","atin":"AT00000001","ownerKind":"PLATFORM","ownerOrgId":"org_x",
			"status":"LIVE","title":"Gari","categoryId":"c1","sellerHidden":true,"brand":"Mama","communities":["Nigerian"],
			"variants":[{"atin":"AT0000000A","priceMinor":899,"currency":"USD"}],"description":"d","keywords":["k"]},
			"version":"2026-10-01T10:00:00Z","rating":{"average":4.5,"count":2,"updatedAt":"2026-10-02T00:00:00Z"}},
			{"product":{"atin":"AT00000002"},"version":"2026-10-01T10:00:00Z","rating":null}],"nextAfter":"11111111-1111-1111-1111-111111111111"}`)
	})

	since := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	page, err := sources.NewCatalog(sources.CatalogConfig{BaseURL: base + "/"}, hc).Products(t.Context(),
		domain.ProductExportQuery{After: "abc", Statuses: []string{"LIVE", "PAUSED"}, Since: since, Size: 50})
	if err != nil {
		t.Fatal(err)
	}
	if path != "/v1/search-export/products" || got.Get("after") != "abc" || got.Get("size") != "50" ||
		got.Get("since") != "2026-10-01T00:00:00Z" || strings.Join(got["status"], ",") != "LIVE,PAUSED" {
		t.Fatalf("request %s %v", path, got)
	}
	if len(page.Items) != 2 || page.NextAfter != "11111111-1111-1111-1111-111111111111" {
		t.Fatalf("page = %+v", page)
	}
	first := page.Items[0]
	if first.Product.ATIN != "AT00000001" || !first.Product.SellerHidden || first.Product.Variants[0].PriceMinor != 899 ||
		first.Product.Keywords[0] != "k" || first.Rating == nil || first.Rating.Average != 4.5 || first.Rating.Count != 2 ||
		!first.Version.Equal(time.Date(2026, 10, 1, 10, 0, 0, 0, time.UTC)) {
		t.Fatalf("item = %+v", first)
	}
	if page.Items[1].Rating != nil {
		t.Fatalf("a product without reviews has no rating: %+v", page.Items[1].Rating)
	}
}

func TestCatalogErrorsCarryTheStatus(t *testing.T) {
	base, hc := serve(t, func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "catalog:read needed", http.StatusForbidden)
	})
	_, err := sources.NewCatalog(sources.CatalogConfig{BaseURL: base}, hc).Products(t.Context(), domain.ProductExportQuery{})
	if err == nil || !strings.Contains(err.Error(), "403") {
		t.Fatalf("err = %v", err)
	}
}

func TestCatalogSendsTheClientCredentialsToken(t *testing.T) {
	var auth string
	idp, _ := serve(t, func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil || r.PostForm.Get("scope") != "catalog:read" {
			http.Error(w, "bad scope", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"access_token":"tok","token_type":"Bearer","expires_in":3600}`)
	})
	base, hc := serve(t, func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Authorization")
		_, _ = fmt.Fprint(w, `{"items":[],"nextAfter":null}`)
	})

	c := sources.NewCatalog(sources.CatalogConfig{BaseURL: base, TokenURL: idp + "/oauth2/token", ClientID: "indexer-service",
		ClientSecret: "s", Scopes: []string{"catalog:read"}}, hc)
	if _, err := c.Products(t.Context(), domain.ProductExportQuery{}); err != nil {
		t.Fatal(err)
	}
	if auth != "Bearer tok" {
		t.Fatalf("Authorization = %q", auth)
	}
}

func TestOffersMapsBuyBoxesAndNoWinner(t *testing.T) {
	var got url.Values
	base, hc := serve(t, func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		_, _ = fmt.Fprint(w, `{"items":[{"atin":"V1","buyBox":{"offerId":"o1","availableQty":7}},{"atin":"V2","buyBox":null}]}`)
	})

	boxes, err := sources.NewOffers(base, hc).BuyBoxes(t.Context(), []string{"V1", "V2", "V3"})
	if err != nil {
		t.Fatal(err)
	}
	if got.Get("atins") != "V1,V2,V3" || got.Get("by") != "variant" {
		t.Fatalf("query = %v", got)
	}
	if len(boxes) != 2 || boxes[0] != (domain.VariantBuyBox{ATIN: "V1", WinnerOfferID: "o1", AvailableQty: 7}) ||
		boxes[1] != (domain.VariantBuyBox{ATIN: "V2"}) {
		t.Fatalf("boxes = %+v", boxes)
	}
}

func TestOffersRefusesMoreThanTheEndpointAnswers(t *testing.T) {
	atins := make([]string, sources.MaxBuyBoxATINs+1)
	if _, err := sources.NewOffers("http://unused", http.DefaultClient).BuyBoxes(t.Context(), atins); err == nil {
		t.Fatal("expected a batch size error")
	}
}

func TestInventoryFlattensNodes(t *testing.T) {
	base, hc := serve(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("skus") != "V1,V2" {
			http.Error(w, "skus", http.StatusBadRequest)
			return
		}
		_, _ = fmt.Fprint(w, `[{"sku":"V1","available":12,"inStock":true,"nodes":[{"nodeId":"dc1","available":9,"version":4},{"nodeId":"dc2","available":3,"version":5}]},
			{"sku":"V2","available":0,"nodes":[]}]`)
	})

	stock, err := sources.NewInventory(base, hc).Stock(t.Context(), []string{"V1", "V2"})
	if err != nil {
		t.Fatal(err)
	}
	if len(stock) != 2 || stock[0] != (domain.NodeStock{SKU: "V1", Node: "dc1", Available: 9, Version: 4}) ||
		stock[1] != (domain.NodeStock{SKU: "V1", Node: "dc2", Available: 3, Version: 5}) {
		t.Fatalf("stock = %+v", stock)
	}
}

func TestInventoryRefusesMoreThanTheEndpointAnswersAndSurfacesFailures(t *testing.T) {
	if _, err := sources.NewInventory("http://unused", http.DefaultClient).Stock(t.Context(), make([]string, sources.MaxStockSKUs+1)); err == nil {
		t.Fatal("expected a batch size error")
	}
	base, hc := serve(t, func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "boom", http.StatusBadGateway) })
	if _, err := sources.NewInventory(base, hc).Stock(t.Context(), []string{"V1"}); err == nil || !strings.Contains(err.Error(), "502") {
		t.Fatalf("err = %v", err)
	}
}

func TestEmptyBatchesDoNotCallAnything(t *testing.T) {
	if got, err := sources.NewOffers("http://unused", http.DefaultClient).BuyBoxes(t.Context(), nil); err != nil || got != nil {
		t.Fatalf("%v %v", got, err)
	}
	if got, err := sources.NewInventory("http://unused", http.DefaultClient).Stock(t.Context(), nil); err != nil || got != nil {
		t.Fatalf("%v %v", got, err)
	}
}
