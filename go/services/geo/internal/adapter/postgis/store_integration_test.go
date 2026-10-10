package postgis_test

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/postgis"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

var (
	sharedPool *pgxpool.Pool
	stopShared func()
)

func TestMain(m *testing.M) {
	code := m.Run()
	if stopShared != nil {
		stopShared()
	}
	os.Exit(code)
}

// postgisPool starts one local postgis/postgis:17-3.5 container for the package (Docker Hub is never needed).
func postgisPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	if sharedPool != nil {
		return sharedPool
	}
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "postgis/postgis:17-3.5",
		testcontainers.WithEnv(map[string]string{"POSTGRES_USER": "geo", "POSTGRES_PASSWORD": "geo", "POSTGRES_DB": "geo"}),
		testcontainers.WithExposedPorts("5432/tcp"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(2*time.Minute)),
	)
	if err != nil {
		t.Fatal(err)
	}
	hostPort, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		_ = ctr.Terminate(ctx)
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://geo:geo@"+hostPort+"/geo")
	if err != nil {
		_ = ctr.Terminate(ctx)
		t.Fatal(err)
	}
	if err := postgis.Migrate(ctx, pool, geo.Files); err != nil {
		t.Fatal(err)
	}
	sharedPool = pool
	stopShared = func() {
		pool.Close()
		_ = ctr.Terminate(context.Background())
	}
	return pool
}

func TestZonesContainPoints(t *testing.T) {
	ctx := context.Background()
	pool := postgisPool(t)
	store := postgis.New(pool)

	// Houston delivery polygon around downtown and a 15-mile braider service area centred in Edison, NJ.
	_, err := store.Save(ctx, domain.Zone{Type: domain.ZoneDelivery, OwnerID: "platform", Name: "Houston core", Active: true,
		GeoJSON: `{"type":"Polygon","coordinates":[[[-95.50,29.65],[-95.25,29.65],[-95.25,29.85],[-95.50,29.85],[-95.50,29.65]]]}`})
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.Save(ctx, domain.Zone{Type: domain.ZoneServiceArea, OwnerID: "prov_ama", Name: "Ama's Braiding",
		CenterLat: 40.5187, CenterLon: -74.4121, RadiusMiles: 15, Active: true})
	if err != nil {
		t.Fatal(err)
	}

	in, err := store.Containing(ctx, 29.7589, -95.3677, domain.ZoneDelivery)
	if err != nil || len(in) != 1 || in[0].Name != "Houston core" {
		t.Fatalf("downtown Houston: %+v %v", in, err)
	}
	out, err := store.Containing(ctx, 29.5941, -95.6243, domain.ZoneDelivery) // Sugar Land
	if err != nil || len(out) != 0 {
		t.Fatalf("Sugar Land should be outside: %+v %v", out, err)
	}
	near, err := store.Containing(ctx, 40.5781, -74.3532, domain.ZoneServiceArea) // Oak Tree Rd, ~5 miles
	if err != nil || len(near) != 1 || near[0].RadiusMiles < 14.9 {
		t.Fatalf("Edison should be within 15 miles: %+v %v", near, err)
	}
	far, err := store.Containing(ctx, 40.7383, -74.1735, domain.ZoneServiceArea) // Newark, ~20 miles
	if err != nil || len(far) != 0 {
		t.Fatalf("Newark should be outside: %+v %v", far, err)
	}
}

func TestGeodesicDistanceAndZonesNear(t *testing.T) {
	ctx := context.Background()
	pool := postgisPool(t)
	calc := postgis.NewDistanceCalculator(pool)

	downtown := domain.Point{Lat: 29.7589, Lng: -95.3677}
	got, err := calc.Geodesic(ctx, downtown, []domain.Point{
		{Lat: 29.6713, Lng: -95.5446}, // 9800 Bissonnet St, ~12 miles
		{Lat: 29.5941, Lng: -95.6243}, // Sugar Land, ~19 miles
		downtown,
	})
	if err != nil || len(got) != 3 {
		t.Fatalf("geodesic: %v %v", got, err)
	}
	if mi := domain.MetresToMiles(got[0]); mi < 12.0 || mi > 12.5 {
		t.Fatalf("Bissonnet: %v miles", mi)
	}
	if mi := domain.MetresToMiles(got[1]); mi < 18.8 || mi > 19.4 {
		t.Fatalf("Sugar Land: %v miles", mi)
	}
	if got[2] != 0 {
		t.Fatalf("same point: %v", got[2])
	}
	empty, err := calc.Geodesic(ctx, downtown, nil)
	if err != nil || len(empty) != 0 {
		t.Fatalf("no destinations: %v %v", empty, err)
	}

	store := postgis.New(pool)
	if _, err := store.Save(ctx, domain.Zone{Type: domain.ZoneDelivery, OwnerID: "platform", Name: "Pickup core",
		Active: true, GeoJSON: `{"type":"Polygon","coordinates":[[[-95.40,29.74],[-95.33,29.74],[-95.33,29.78],` +
			`[-95.40,29.78],[-95.40,29.74]]]}`}); err != nil {
		t.Fatal(err)
	}
	inside, err := store.Within(ctx, 29.7589, -95.3677, domain.ZoneDelivery, 0)
	if err != nil || len(inside) == 0 || !inside[0].Inside || inside[0].DistanceMiles != 0 {
		t.Fatalf("inside: %+v %v", inside, err)
	}
	// 9800 Bissonnet (-95.5446) is outside "Houston core" (saved by TestZonesContainPoints, -95.50…-95.25), about
	// 2.7 miles west of its edge, and ~9 miles from "Pickup core".
	near, err := store.Within(ctx, 29.6713, -95.5446, domain.ZoneDelivery, 5*domain.MetresPerMile)
	if err != nil || len(near) != 1 || near[0].Name != "Houston core" || near[0].Inside ||
		near[0].DistanceMiles < 2 || near[0].DistanceMiles > 3.5 {
		t.Fatalf("near: %+v %v", near, err)
	}
	far, err := store.Within(ctx, 29.6713, -95.5446, domain.ZoneDelivery, domain.MetresPerMile)
	if err != nil || len(far) != 0 {
		t.Fatalf("1 mile: %+v %v", far, err)
	}
}
