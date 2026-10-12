package pgrepo_test

import (
	"context"
	"errors"
	"slices"
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

// TestImageLibraryQueries covers the library index (S310): the list with its filters and paging, the SKU and
// file-name lookup (usable images only) and removal.
func TestImageLibraryQueries(t *testing.T) {
	pool := startPostgres(t)
	ctx := context.Background()
	if err := pgrepo.Migrate(ctx, pool, media.Files); err != nil {
		t.Fatal(err)
	}
	repo := pgrepo.New(pool)
	t0 := time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC)
	lib := func(id, org, name, sku string, slot domain.LibrarySlot, status domain.Status, url string, at int) {
		a := asset(id, org, uint64(at))
		a.FileName, a.SKU, a.Slot, a.Library, a.CreatedAt = name, sku, slot, true, t0.Add(time.Duration(at)*time.Minute)
		if err := repo.Insert(ctx, a); err != nil {
			t.Fatal(err)
		}
		if status != domain.StatusPending {
			got, err := repo.Get(ctx, id)
			if err != nil {
				t.Fatal(err)
			}
			got.Status, got.URL = status, url
			if err := repo.Update(ctx, got); err != nil {
				t.Fatal(err)
			}
		}
	}
	const (
		main1  = "a1000000-0000-4000-8000-000000000001"
		main2  = "a1000000-0000-4000-8000-000000000002"
		pt1    = "a1000000-0000-4000-8000-000000000003"
		held   = "a1000000-0000-4000-8000-000000000004"
		front  = "a1000000-0000-4000-8000-000000000005"
		reject = "a1000000-0000-4000-8000-000000000006"
		pend   = "a1000000-0000-4000-8000-000000000007"
		other  = "a1000000-0000-4000-8000-000000000008"
		plain  = "a1000000-0000-4000-8000-000000000009"
	)
	lib(main1, "org_a", "AWD-M.MAIN.jpg", "AWD-M", domain.SlotMain, domain.StatusReady, "https://cdn/1.jpg", 1)
	lib(main2, "org_a", "awd-m_main.jpg", "AWD-M", domain.SlotMain, domain.StatusReady, "https://cdn/2.jpg", 2)
	lib(pt1, "org_a", "AWD-M.PT01.jpg", "AWD-M", domain.OtherSlot(1), domain.StatusReady, "https://cdn/3.jpg", 3)
	lib(held, "org_a", "AWD-M.SWCH.jpg", "AWD-M", domain.SlotSwatch, domain.StatusFlagged, "", 4)
	lib(front, "org_a", "Front_100%.jpg", "", "", domain.StatusReady, "https://cdn/5.jpg", 5)
	lib(reject, "org_a", "BAD.MAIN.jpg", "BAD", domain.SlotMain, domain.StatusRejected, "", 6)
	lib(pend, "org_a", "LATE.MAIN.jpg", "LATE", domain.SlotMain, domain.StatusPending, "", 7)
	lib(other, "org_b", "AWD-M.MAIN.jpg", "AWD-M", domain.SlotMain, domain.StatusReady, "https://cdn/8.jpg", 8)
	notLib := asset(plain, "org_a", 9)
	if err := repo.Insert(ctx, notLib); err != nil {
		t.Fatal(err)
	}

	ids := func(as []domain.Asset) []string {
		out := []string{}
		for _, a := range as {
			out = append(out, a.ID)
		}
		return out
	}
	list := func(q domain.LibraryQuery) ([]string, int) {
		if q.Size == 0 {
			q.Size = 50
		}
		as, total, err := repo.ListLibrary(ctx, "org_a", q)
		if err != nil {
			t.Fatal(err)
		}
		return ids(as), total
	}
	if got, total := list(domain.LibraryQuery{}); total != 6 || len(got) != 6 || got[0] != reject || got[5] != main1 {
		t.Fatalf("library = %v (%d): completed library images of the org, newest first", got, total)
	}
	if got, total := list(domain.LibraryQuery{Size: 2, Page: 1}); total != 6 || len(got) != 2 || got[0] != held {
		t.Fatalf("page 2 = %v", got)
	}
	if got, _ := list(domain.LibraryQuery{Q: "awd-m"}); len(got) != 4 {
		t.Fatalf("q matches file name or SKU: %v", got)
	}
	if got, _ := list(domain.LibraryQuery{Q: "100%"}); len(got) != 1 || got[0] != front {
		t.Fatalf("q escapes LIKE wildcards: %v", got)
	}
	if got, _ := list(domain.LibraryQuery{Status: "FLAGGED"}); len(got) != 1 || got[0] != held {
		t.Fatalf("held: %v", got)
	}
	if got, _ := list(domain.LibraryQuery{Status: "READY"}); len(got) != 4 {
		t.Fatalf("published: %v", got)
	}
	unmatched := false
	if got, _ := list(domain.LibraryQuery{Matched: &unmatched}); len(got) != 1 || got[0] != front {
		t.Fatalf("names without a SKU: %v", got)
	}

	found, err := repo.LookupLibrary(ctx, "org_a", []string{"AWD-M", "BAD", "LATE"}, []string{"front_100%.jpg"})
	if err != nil {
		t.Fatal(err)
	}
	if got := ids(found); len(got) != 5 || got[0] != front || got[4] != main1 {
		t.Fatalf("lookup = %v: usable images (READY, FLAGGED) by SKU or file name, newest first", got)
	}
	if err := repo.RemoveFromLibrary(ctx, main2); err != nil {
		t.Fatal(err)
	}
	if err := repo.RemoveFromLibrary(ctx, main2); !errors.Is(err, pgrepo.ErrNotFound) {
		t.Fatalf("removing twice: %v", err)
	}
	if err := repo.RemoveFromLibrary(ctx, plain); !errors.Is(err, pgrepo.ErrNotFound) {
		t.Fatalf("not a library image: %v", err)
	}
	if got, total := list(domain.LibraryQuery{}); total != 5 || slices.Contains(got, main2) {
		t.Fatalf("after removal = %v", got)
	}
	if a, err := repo.Get(ctx, main2); err != nil || a.Library || a.URL == "" {
		t.Fatalf("the asset itself stays: %+v %v", a, err)
	}
}
