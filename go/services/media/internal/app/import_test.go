package app_test

import (
	"context"
	"errors"
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

// fakeFetcher serves files by URL (never the network).
type fakeFetcher struct{ files map[string]port.FetchedFile }

func (f fakeFetcher) Fetch(_ context.Context, rawURL string, maxBytes int64) (port.FetchedFile, error) {
	file, ok := f.files[rawURL]
	if !ok {
		return port.FetchedFile{}, errors.Join(domain.ErrRejected, errors.New("the link answered 404 Not Found"))
	}
	if int64(len(file.Data)) > maxBytes {
		return port.FetchedFile{}, domain.ErrRejected
	}
	return file, nil
}

var catalogService = auth.Actor{Subject: domain.ClientCatalogService, Type: "SERVICE",
	Permissions: []string{domain.PermissionMediaImport}}

func importFixture(t *testing.T, files map[string]port.FetchedFile, ai *fakeModerator) (app.MediaService, *memStore) {
	t.Helper()
	st := &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	stores := map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: st}
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithModeration(imageproc.New(40_000_000), ai, domain.ModerationPolicy{DuplicateDistance: -1}, time.Second),
		app.WithFetcher(fakeFetcher{files: files}))
	return svc, st
}

func TestImportedImagesAreQuarantinedModeratedAndOwnedByTheUploadingOrg(t *testing.T) {
	photo := withGPS(productPhoto(t, 2))
	svc, st := importFixture(t, map[string]port.FetchedFile{
		"https://cdn.example.com/a.jpg": {Data: photo, ContentType: "application/octet-stream", FileName: "a.jpg"},
	}, &fakeModerator{verdict: allow})
	a, err := svc.ImportFromURL(context.Background(), catalogService, domain.PurposeProductImage,
		"https://cdn.example.com/a.jpg", "org_ade", "member-1")
	if err != nil {
		t.Fatal(err)
	}
	if a.Status != domain.StatusReady || a.OwnerOrg != "org_ade" || a.UploadedBy != "member-1" || a.ContentType != "image/jpeg" ||
		a.Width != 1200 || a.Moderation == nil || !a.Moderation.MetadataStripped || a.URL == "" {
		t.Fatalf("imported asset = %+v", a)
	}
	for key := range st.objects {
		if strings.HasPrefix(key, domain.QuarantinePrefix) {
			t.Fatalf("the quarantined copy must be removed after publishing: %s", key)
		}
	}
}

func TestImportRefusesOtherCallersPurposesAndNonImages(t *testing.T) {
	svc, _ := importFixture(t, map[string]port.FetchedFile{
		"https://cdn.example.com/page.html": {Data: []byte("<html>hi</html>"), ContentType: "image/jpeg"},
	}, nil)
	ctx := context.Background()
	_, err := svc.ImportFromURL(ctx, sellerMember, domain.PurposeProductImage, "https://cdn.example.com/a.jpg", "org_ade", "")
	if !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("a seller token cannot import: %v", err)
	}
	noScope := auth.Actor{Subject: domain.ClientCatalogService, Type: "SERVICE"}
	if _, err := svc.ImportFromURL(ctx, noScope, domain.PurposeProductImage, "https://x", "org_ade", ""); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("catalog needs media:import: %v", err)
	}
	if _, err := svc.ImportFromURL(ctx, catalogService, domain.PurposeKYCDocument, "https://x", "org_ade", ""); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("only product images: %v", err)
	}
	if _, err := svc.ImportFromURL(ctx, catalogService, domain.PurposeProductImage, "https://cdn.example.com/page.html", "org_ade", ""); err == nil ||
		!strings.Contains(err.Error(), "not a JPG, PNG or WebP") {
		t.Fatalf("the bytes decide the type: %v", err)
	}
	if _, err := svc.ImportFromURL(ctx, catalogService, domain.PurposeProductImage, "https://cdn.example.com/missing.jpg", "org_ade", ""); !errors.Is(err, domain.ErrRejected) ||
		!strings.Contains(err.Error(), "404") {
		t.Fatalf("unreachable links are rejected with the reason: %v", err)
	}
	if _, err := svc.ImportFromURL(ctx, catalogService, domain.PurposeProductImage, "https://cdn.example.com/a.jpg", " ", ""); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("an owner is required: %v", err)
	}
}
