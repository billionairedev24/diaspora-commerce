package app_test

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// memLibrary filters memRepo's assets like the SQL of pgrepo (completed library images, newest first).
type memLibrary struct{ repo *memRepo }

func (l memLibrary) sorted(org string) []domain.Asset {
	var out []domain.Asset
	for _, a := range l.repo.assets {
		if a.OwnerOrg == org && a.Library && a.Status != domain.StatusPending {
			out = append(out, a)
		}
	}
	slices.SortFunc(out, func(x, y domain.Asset) int { return y.CreatedAt.Compare(x.CreatedAt) })
	return out
}

func (l memLibrary) ListLibrary(_ context.Context, org string, q domain.LibraryQuery) ([]domain.Asset, int, error) {
	var out []domain.Asset
	for _, a := range l.sorted(org) {
		if q.Q != "" && !strings.Contains(strings.ToLower(a.FileName+" "+a.SKU), strings.ToLower(q.Q)) {
			continue
		}
		if q.Status != "" && string(domain.LibraryStatus(a)) != q.Status {
			continue
		}
		out = append(out, a)
	}
	from := min(q.Page*q.Size, len(out))
	return out[from:min(from+q.Size, len(out))], len(out), nil
}

func (l memLibrary) LookupLibrary(_ context.Context, org string, skus, fileNames []string) ([]domain.Asset, error) {
	var out []domain.Asset
	for _, a := range l.sorted(org) {
		if a.Status != domain.StatusReady && a.Status != domain.StatusFlagged {
			continue
		}
		if slices.Contains(skus, a.SKU) || slices.ContainsFunc(fileNames, func(f string) bool { return strings.EqualFold(f, a.FileName) }) {
			out = append(out, a)
		}
	}
	return out, nil
}

func (l memLibrary) RemoveFromLibrary(_ context.Context, id string) error {
	a := l.repo.assets[id]
	a.Library = false
	l.repo.assets[id] = a
	return nil
}

type libraryFixture struct {
	svc   app.MediaService
	store *memStore
	repo  *memRepo
	ai    *fakeModerator
	clock time.Time
}

func newLibrary(ai *fakeModerator) *libraryFixture {
	st := &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	f := &libraryFixture{store: st, repo: repo, ai: ai, clock: time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC)}
	f.svc = app.NewMediaService(map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: st}, repo,
		inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithModeration(imageproc.New(40_000_000), ai, domain.ModerationPolicy{DuplicateDistance: -1}, time.Second),
		app.WithLibrary(memLibrary{repo: repo}), app.WithClock(func() time.Time {
			f.clock = f.clock.Add(time.Second)
			return f.clock
		}))
	return f
}

func (f *libraryFixture) upload(t *testing.T, actor auth.Actor, name string, seed int) domain.Asset {
	t.Helper()
	ctx := context.Background()
	a, up, err := f.svc.RequestLibraryUpload(ctx, actor, name, "image/jpeg", 1000)
	if err != nil {
		t.Fatal(err)
	}
	f.store.objects[up.Key] = productPhoto(t, seed)
	done, err := f.svc.Complete(ctx, actor, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	return done
}

var catalogImporter = auth.Actor{Subject: domain.ClientCatalogService, Type: "SERVICE", Permissions: []string{domain.PermissionMediaImport}}

func TestLibraryUploadParsesTheFileNameAndGoesThroughModeration(t *testing.T) {
	f := newLibrary(&fakeModerator{verdict: allow})
	ctx := context.Background()
	a, up, err := f.svc.RequestLibraryUpload(ctx, sellerMember, "summer/awd-m_main.JPG", "image/jpeg", 1000)
	if err != nil {
		t.Fatal(err)
	}
	if !a.Library || a.SKU != "AWD-M" || a.Slot != domain.SlotMain || a.FileName != "awd-m_main.JPG" ||
		a.Purpose != domain.PurposeProductImage || !strings.HasPrefix(up.Key, domain.QuarantinePrefix) {
		t.Fatalf("library asset = %+v (key %s)", a, up.Key)
	}
	f.store.objects[up.Key] = productPhoto(t, 1)
	done, err := f.svc.Complete(ctx, sellerMember, a.ID)
	if err != nil || done.Status != domain.StatusReady || done.URL == "" || f.ai.calls != 1 {
		t.Fatalf("library images are moderated like every product image: %+v %v (ai calls %d)", done, err, f.ai.calls)
	}
	items, total, err := f.svc.ListLibrary(ctx, sellerMember, domain.LibraryQuery{})
	if err != nil || total != 1 || items[0].Status != domain.StatusReady || items[0].URL == nil || *items[0].SKU != "AWD-M" {
		t.Fatalf("list = %+v %d %v", items, total, err)
	}
	other := auth.Actor{Subject: "u9", Type: "SELLER_MEMBER", OrgID: "org_other"}
	if items, total, _ := f.svc.ListLibrary(ctx, other, domain.LibraryQuery{}); total != 0 || len(items) != 0 {
		t.Fatalf("another organisation sees nothing: %+v", items)
	}
}

func TestLibraryNeedsAnOrganisationMember(t *testing.T) {
	f := newLibrary(&fakeModerator{verdict: allow})
	ctx := context.Background()
	customer := auth.Actor{Subject: "c1", Type: "CUSTOMER"}
	if _, _, err := f.svc.RequestLibraryUpload(ctx, customer, "SKU.MAIN.jpg", "image/jpeg", 1000); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("customer upload: %v", err)
	}
	if _, _, err := f.svc.RequestLibraryUpload(ctx, catalogImporter, "SKU.MAIN.jpg", "image/jpeg", 1000); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("service upload: %v", err)
	}
	if _, _, err := f.svc.ListLibrary(ctx, customer, domain.LibraryQuery{}); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("customer list: %v", err)
	}
	if _, _, err := f.svc.RequestLibraryUpload(ctx, sellerMember, "SKU.MAIN.gif", "image/gif", 1000); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("images only: %v", err)
	}
	if _, _, err := f.svc.RequestLibraryUpload(ctx, sellerMember, "SKU.MAIN.jpg", "image/jpeg", 9<<20); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("8 MB per image: %v", err)
	}
	if _, _, err := f.svc.ListLibrary(ctx, sellerMember, domain.LibraryQuery{Size: 500}); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("size is capped: %v", err)
	}
}

func TestLookupReturnsTheNewestImageOfEachSlotAndFileName(t *testing.T) {
	f := newLibrary(&fakeModerator{verdict: allow})
	ctx := context.Background()
	old := f.upload(t, sellerMember, "AWD-M.MAIN.jpg", 1)
	newer := f.upload(t, sellerMember, "awd-m.main.jpg", 2)
	pt1 := f.upload(t, sellerMember, "AWD-M.PT01.jpg", 3)
	front := f.upload(t, sellerMember, "front.jpg", 4)
	f.upload(t, sellerMember, "OTHER.MAIN.jpg", 5)
	f.ai.verdict = domain.AIVerdict{Decision: domain.AIReview, Labels: []string{"WATERMARK"}, Model: "fake/vision"}
	held := f.upload(t, sellerMember, "AWD-M.SWCH.jpg", 6)
	if held.Status != domain.StatusFlagged || held.URL != "" {
		t.Fatalf("held = %+v", held)
	}

	got, err := f.svc.LookupLibrary(ctx, catalogImporter, "org_lagos", []string{"awd-m"}, []string{"FRONT.JPG", "missing.jpg"})
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]domain.LibraryImage{}
	for _, g := range got {
		ids[g.AssetID] = g
	}
	if len(got) != 4 || ids[newer.ID].AssetID == "" || ids[pt1.ID].AssetID == "" || ids[front.ID].AssetID == "" || ids[old.ID].AssetID != "" {
		t.Fatalf("lookup = %+v", got)
	}
	if h := ids[held.ID]; h.Status != domain.StatusFlagged || h.URL != nil {
		t.Fatalf("held swatch comes back FLAGGED without a URL: %+v", h)
	}
	if _, err := f.svc.LookupLibrary(ctx, sellerMember, "org_lagos", []string{"AWD-M"}, nil); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("members never look up: %v", err)
	}
	noScope := catalogImporter
	noScope.Permissions = nil
	if _, err := f.svc.LookupLibrary(ctx, noScope, "org_lagos", []string{"AWD-M"}, nil); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("media:import is required: %v", err)
	}
	if _, err := f.svc.LookupLibrary(ctx, catalogImporter, "", []string{"AWD-M"}, nil); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("ownerOrg is required: %v", err)
	}
	if _, err := f.svc.LookupLibrary(ctx, catalogImporter, "org_lagos", make([]string, app.MaxLibraryLookup+1), nil); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("lookups are bounded: %v", err)
	}
	if got, _ := f.svc.LookupLibrary(ctx, catalogImporter, "org_other", []string{"AWD-M"}, nil); len(got) != 0 {
		t.Fatalf("another organisation's library: %+v", got)
	}
}

func TestRemoveFromLibraryKeepsTheFile(t *testing.T) {
	f := newLibrary(&fakeModerator{verdict: allow})
	ctx := context.Background()
	a := f.upload(t, sellerMember, "AWD-M.MAIN.jpg", 1)
	other := auth.Actor{Subject: "u9", Type: "SELLER_MEMBER", OrgID: "org_other"}
	if err := f.svc.RemoveFromLibrary(ctx, other, a.ID); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("another organisation: %v", err)
	}
	if err := f.svc.RemoveFromLibrary(ctx, sellerMember, a.ID); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.RemoveFromLibrary(ctx, sellerMember, a.ID); !errors.Is(err, app.ErrNotInLibrary) {
		t.Fatalf("twice: %v", err)
	}
	if got, _ := f.svc.LookupLibrary(ctx, catalogImporter, "org_lagos", []string{"AWD-M"}, nil); len(got) != 0 {
		t.Fatalf("removed images are not matched any more: %+v", got)
	}
	if kept, _ := f.svc.Get(ctx, sellerMember, a.ID, 0); kept.URL == "" || len(f.store.deleted) != 1 {
		t.Fatalf("the published file stays (only the quarantined upload was deleted): %+v deleted=%v", kept, f.store.deleted)
	}
}
