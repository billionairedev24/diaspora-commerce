package httpapi_test

import (
	"context"
	"net/http"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

type zoneBook struct {
	app.ZoneService
	zones []domain.Zone
	saved int
}

func (z *zoneBook) List(context.Context, domain.ZoneType, string) ([]domain.Zone, error) {
	return z.zones, nil
}

func (z *zoneBook) Save(_ context.Context, in domain.Zone) (domain.Zone, error) {
	z.saved++
	return in, nil
}

// TestZoneListHidesDraftZonesFromEveryoneWithoutZoneWrite: GET /v1/zones is open to any signed-in person, so zones a
// provider or the platform has not switched on (a draft service area, a planned catchment) are staff-only data.
func TestZoneListHidesDraftZonesFromEveryoneWithoutZoneWrite(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.StaffPerms(httpapi.ZonesWrite))
	book := &zoneBook{zones: []domain.Zone{
		{ID: "z-live", Type: domain.ZoneServiceArea, OwnerID: w.OrgB, Name: "Live area", RadiusMiles: 10, Active: true},
		{ID: "z-draft", Type: domain.ZoneServiceArea, OwnerID: w.OrgB, Name: "SECRET draft area", RadiusMiles: 10},
	}}
	mux := http.NewServeMux()
	httpapi.New(nil, nil, book, nil).Register(mux, w.Auth(true), w.Auth(false))

	for _, p := range []tenanttest.Principal{w.SellerA, w.CustomerA} {
		res := tenanttest.Do(mux, p, tenanttest.Get("/v1/zones?ownerId="+w.OrgB))
		tenanttest.All(tenanttest.Allowed(), tenanttest.Contains("Live area"), tenanttest.Leaks("SECRET draft area", "z-draft"))(t, res)
	}
	tenanttest.Run(t, mux, []tenanttest.Case{
		{Name: "anonymous cannot list zones", As: w.Anonymous, Req: tenanttest.Get("/v1/zones"), Expect: tenanttest.Status(http.StatusUnauthorized)},
		{Name: "staff sees drafts", As: w.Staff, Req: tenanttest.Get("/v1/zones"), Expect: tenanttest.Contains("SECRET draft area")},
	})
	tenanttest.CrossTenant(t, mux, []tenanttest.Principal{w.SellerA, w.SellerB, w.CustomerA, w.Anonymous}, []tenanttest.Request{
		tenanttest.Send(http.MethodPost, "/v1/zones", `{"type":"SERVICE_AREA","ownerId":"`+w.OrgB+`","name":"x","radiusMiles":5}`),
	})
	if book.saved != 0 {
		t.Fatal("a non-staff caller saved a zone")
	}
}

func TestRouteMatrix(t *testing.T) {
	m, err := geo.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "."), string(m), ".")
}
