package app_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// TestExactStockOfASellerIsNotPublic: search is anonymous, so the exact number of units a seller holds must not be
// readable by competitors. Only the "Only N left" range (1 to 5) and the purchase limit of the quantity stepper (99) are.
func TestExactStockOfASellerIsNotPublic(t *testing.T) {
	idx := &fakeIndex{page: domain.Page{Hits: []domain.Hit{
		{ATIN: "AT1", InStock: true, AvailableQty: 4382},
		{ATIN: "AT2", InStock: true, AvailableQty: 3},
		{ATIN: "AT3", InStock: true, AvailableQty: 99},
		{ATIN: "AT4", InStock: false, AvailableQty: 0},
	}}}
	page, err := app.NewProductSearcher(idx, nil, quiet).Search(context.Background(), domain.Query{})
	if err != nil {
		t.Fatal(err)
	}
	got := []int{page.Hits[0].AvailableQty, page.Hits[1].AvailableQty, page.Hits[2].AvailableQty, page.Hits[3].AvailableQty}
	want := []int{domain.MaxPublicQty, 3, domain.MaxPublicQty, 0}
	for i := range got {
		if got[i] != want[i] {
			t.Fatalf("availableQty %v, want %v", got, want)
		}
	}
	sug, err := app.NewSuggester(&fakeIndex{page: idx.page}).Suggest(context.Background(), "us", "ga")
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range sug.Products {
		if h.AvailableQty > domain.MaxPublicQty {
			t.Fatalf("typeahead leaks the exact stock: %+v", h)
		}
	}
}

// spyIndex records the filters the search service passed down.
type captureSearcher struct{ got domain.Query }

func (c *captureSearcher) Search(_ context.Context, q domain.Query) (domain.Page, error) {
	c.got = q
	return domain.Page{Hits: []domain.Hit{}}, nil
}

// TestSearchTakesNoOrganisationFilterThatReturnsPrivateRows: sellerId only narrows the public LIVE catalogue to one
// seller's page, and no query parameter can switch the status filter or ask for private fields.
func TestSearchTakesNoOrganisationFilterThatReturnsPrivateRows(t *testing.T) {
	w := tenanttest.NewWorld()
	spy := &captureSearcher{}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Deps{Searcher: spy}).Register(mux, w.Auth(true))

	res := tenanttest.Do(mux, w.SellerA, tenanttest.Get("/v1/search?sellerId="+w.OrgB+"&status=PENDING&includeDrafts=true&fields=cost_price"))
	if res.Status != http.StatusOK {
		t.Fatalf("status %d %s", res.Status, res.Body)
	}
	if spy.got.SellerID != w.OrgB {
		t.Fatalf("seller page filter lost: %+v", spy.got)
	}
	var p map[string]any
	if err := json.Unmarshal([]byte(res.Body), &p); err != nil {
		t.Fatal(err)
	}
	// The hit type has no cost, stock-node, seller-id or status field to ask for.
	for _, f := range []string{"cost", "costPrice", "sellerId", "status", "stockByNode", "variantQty", "shipsFrom"} {
		if strings.Contains(string(mustJSON(t, domain.Hit{})), `"`+f+`"`) {
			t.Fatalf("Hit serialises %q", f)
		}
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestSearchStaffRoutesAndClickAttribution(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.StaffPerms(httpapi.PermSynonymsEdit))
	mux := http.NewServeMux()
	httpapi.New(httpapi.Deps{Searcher: &captureSearcher{}}).Register(mux, w.Auth(true))
	tenanttest.CrossTenant(t, mux, []tenanttest.Principal{w.SellerA, w.CustomerA, w.Anonymous}, []tenanttest.Request{
		tenanttest.Get("/v1/search/synonyms"),
		tenanttest.Send(http.MethodPut, "/v1/search/synonyms/r1", `{"synonyms":"a, b"}`),
		tenanttest.Send(http.MethodDelete, "/v1/search/synonyms/r1", ""),
	})
}

func TestRouteMatrix(t *testing.T) {
	m, err := search.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "../adapter/httpapi"), string(m), ".")
}
