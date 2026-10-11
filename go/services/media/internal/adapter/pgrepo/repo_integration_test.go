package pgrepo_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// image is the local PostgreSQL image (Docker Hub is rate-limited; never pull another tag in CI).
const image = "postgres:17"

func startPostgres(t *testing.T) *pgxpool.Pool {
	t.Helper()
	testdocker.Require(t)
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, image,
		testcontainers.WithEnv(map[string]string{"POSTGRES_USER": "media", "POSTGRES_PASSWORD": "media", "POSTGRES_DB": "media"}),
		testcontainers.WithExposedPorts("5432/tcp"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(2*time.Minute)),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	endpoint, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://media:media@"+endpoint+"/media?sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func asset(id, org string, hash uint64) domain.Asset {
	h := hash
	return domain.Asset{ID: id, OwnerOrg: org, UploadedBy: "u1", Purpose: domain.PurposeProductImage, Provider: "mem",
		Key: "quarantine/" + id, FileName: "a.jpg", ContentType: "image/jpeg", Bytes: 10, Status: domain.StatusPending,
		Flags: []string{}, CreatedAt: time.Now().UTC(), PHash: &h}
}

func TestMigrationsModerationHashesAndOptimisticLocking(t *testing.T) {
	pool := startPostgres(t)
	ctx := context.Background()
	for range 2 { // migrations run on every start
		if err := pgrepo.Migrate(ctx, pool, media.Files); err != nil {
			t.Fatal(err)
		}
	}
	repo := pgrepo.New(pool)
	const (
		a1 = "11111111-1111-4111-8111-111111111111"
		a2 = "22222222-2222-4222-8222-222222222222"
		a3 = "33333333-3333-4333-8333-333333333333"
		a4 = "44444444-4444-4444-8444-444444444444"
	)
	base := uint64(0xF0F0_F0F0_0F0F_0F0F) // high bit set: stored as a negative bigint
	for _, a := range []domain.Asset{
		asset(a1, "org_a", base),
		asset(a2, "org_b", base^0b111),        // distance 3
		asset(a3, "org_c", ^base),             // distance 64
		asset(a4, "org_d", base^0b1111111111), // distance 10
	} {
		if err := repo.Insert(ctx, a); err != nil {
			t.Fatal(err)
		}
	}
	got, err := repo.Get(ctx, a1)
	if err != nil || got.PHash == nil || *got.PHash != base || got.Version != 0 {
		t.Fatalf("get: %+v %v", got, err)
	}
	// Only completed images take part in duplicate detection.
	if sim, _ := repo.SimilarImages(ctx, []domain.Purpose{domain.PurposeProductImage}, base, 6, "", 5); len(sim) != 0 {
		t.Fatalf("pending images must not match: %+v", sim)
	}
	for _, id := range []string{a1, a2, a3, a4} {
		cur, _ := repo.Get(ctx, id)
		cur.Status = domain.StatusReady
		cur.Key = "product-image/" + id
		cur.URL = "https://cdn/" + cur.Key
		cur.Moderation = &domain.Moderation{Verdict: domain.VerdictPassed, Reasons: []string{}, AI: domain.AIChecked,
			MetadataStripped: true}
		if err := repo.Update(ctx, cur); err != nil {
			t.Fatal(err)
		}
	}
	sim, err := repo.SimilarImages(ctx, []domain.Purpose{domain.PurposeProductImage}, base, 6, a1, 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(sim) != 1 || sim[0].AssetID != a2 || sim[0].OwnerOrg != "org_b" || sim[0].Distance != 3 {
		t.Fatalf("similar images %+v", sim)
	}
	if sim, _ := repo.SimilarImages(ctx, []domain.Purpose{domain.PurposeStorefront}, base, 6, a1, 5); len(sim) != 0 {
		t.Fatalf("other purposes must not match: %+v", sim)
	}

	got, _ = repo.Get(ctx, a1)
	if got.Key != "product-image/"+a1 || got.Moderation == nil || !got.Moderation.MetadataStripped || got.Version != 1 {
		t.Fatalf("updated asset %+v %+v", got, got.Moderation)
	}
	stale := got
	got.Status = domain.StatusFlagged
	if err := repo.Update(ctx, got); err != nil {
		t.Fatal(err)
	}
	stale.Status = domain.StatusRejected
	if err := repo.Update(ctx, stale); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("a stale update must conflict, got %v", err)
	}
}
