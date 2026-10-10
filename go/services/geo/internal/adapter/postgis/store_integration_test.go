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

func TestZonesContainPoints(t *testing.T) {
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
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
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	hostPort, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://geo:geo@"+hostPort+"/geo")
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := postgis.Migrate(ctx, pool, geo.Files); err != nil {
		t.Fatal(err)
	}
	store := postgis.New(pool)

	// Houston delivery polygon around downtown and a 15-mile braider service area centred in Edison, NJ.
	_, err = store.Save(ctx, domain.Zone{Type: domain.ZoneDelivery, OwnerID: "platform", Name: "Houston core", Active: true,
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
