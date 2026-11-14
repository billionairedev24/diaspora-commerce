package app

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// downPromise is the promise service in the OVH launch deployment: it is not deployed, so every call fails.
type downPromise struct{}

func (downPromise) Promises(context.Context, string, []port.PromiseOffer) ([]port.PromiseQuote, error) {
	return nil, errors.New("promise: dial tcp: lookup promise: no such host")
}

// Production launches WITHOUT the promise service (docs/runbooks/ovh-production.md). The BFF must keep serving the home page:
// only the rail that needs promised delivery days ("deliverable-fast") disappears; every other rail stays and Home does
// not fail. Delivery dates elsewhere are simply not shown (no real basis, no date: ArrivalService).
func TestHomeDegradesWhenThePromiseServiceIsNotDeployed(t *testing.T) {
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"|relevance|": localHits(4), "|rating|": localHits(4)}}}
	pl := domain.Placements{Rails: []domain.RailPlacement{
		{MinItems: 1, ID: "fast", Title: "Get it by {day}", Href: "/s", Source: domain.RailSource{Kind: "deliverable-fast"}},
		{MinItems: 1, ID: "popular", Title: "Popular", Href: "/s", Source: domain.RailSource{Kind: "search", Sort: "rating"}},
	}}
	svc := NewSiteService(SiteDeps{Content: zipContent{pl: pl, zips: &f.zips}, Search: f.search, Catalog: homeCatalog{category: map[string]string{}},
		Inventory: homeInventory{}, Offers: shipsFromOffers{from: nil}, Arrival: NewArrivalService(downPromise{}, nil), Location: zipLocation{}, Estimator: est,
		Market: "us", Clock: func() time.Time { return homeNow }})

	home, err := svc.Home(context.Background(), guestAt("77042"))
	if err != nil {
		t.Fatalf("Home must not fail when promise is down: %v", err)
	}
	if railByID(home, "fast") != nil {
		t.Errorf("the deliverable-fast rail needs promised days and must be dropped, got %+v", home.Rails)
	}
	if railByID(home, "popular") == nil {
		t.Errorf("rails that do not need promise must stay: %+v", home.Rails)
	}
}
