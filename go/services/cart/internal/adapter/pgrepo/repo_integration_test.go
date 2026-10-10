package pgrepo_test

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

func TestVersionedSave(t *testing.T) {
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "postgis/postgis:17-3.5",
		testcontainers.WithEnv(map[string]string{"POSTGRES_USER": "cart", "POSTGRES_PASSWORD": "cart", "POSTGRES_DB": "cart"}),
		testcontainers.WithExposedPorts("5432/tcp"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(2*time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	hp, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://cart:cart@"+hp+"/cart")
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := pgrepo.Migrate(ctx, pool, cart.Files); err != nil {
		t.Fatal(err)
	}
	repo := pgrepo.New(pool)
	owner := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}

	a, _ := repo.Load(ctx, owner)
	b, _ := repo.Load(ctx, owner)
	_ = a.Add(domain.Offer{VariantATIN: "ATA", PriceMinor: 100, Currency: "USD"}, 1, 5, time.Now())
	_ = b.Add(domain.Offer{VariantATIN: "ATB", PriceMinor: 100, Currency: "USD"}, 1, 5, time.Now())
	if err := repo.Save(ctx, &a); err != nil {
		t.Fatal(err)
	}
	if err := repo.Save(ctx, &b); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("stale save must conflict, got %v", err)
	}
	c, _ := repo.Load(ctx, owner)
	if c.Version != 1 || len(c.Lines) != 1 || c.Lines[0].VariantATIN != "ATA" {
		t.Fatalf("stored cart %+v", c)
	}
	_ = c.Add(domain.Offer{VariantATIN: "ATB", PriceMinor: 100, Currency: "USD"}, 2, 5, time.Now())
	if err := repo.Save(ctx, &c); err != nil || c.Version != 2 {
		t.Fatalf("second save: %v version=%d", err, c.Version)
	}
}
