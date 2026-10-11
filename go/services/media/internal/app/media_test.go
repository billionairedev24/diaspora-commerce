package app_test

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"slices"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

type memStore struct {
	objects map[string][]byte
	deleted []string
	maxTTL  time.Duration
	signed  []time.Duration
	puts    []string
}

func (m *memStore) Name() string { return "mem" }
func (m *memStore) PrepareUpload(_ context.Context, key, _, _ string, _ int64, ttl time.Duration) (port.Upload, error) {
	return port.Upload{Method: "PUT", URL: "mem://" + key, Key: key, PublicURL: "https://cdn/" + key, ExpiresAt: time.Now().Add(ttl)}, nil
}
func (m *memStore) Open(_ context.Context, key string, _ int64) (io.ReadCloser, int64, string, error) {
	b, ok := m.objects[key]
	if !ok {
		return nil, 0, "", errors.New("missing")
	}
	return io.NopCloser(bytes.NewReader(b)), int64(len(b)), "", nil
}
func (m *memStore) Delete(_ context.Context, key string) error {
	m.deleted = append(m.deleted, key)
	delete(m.objects, key)
	return nil
}
func (m *memStore) SignedGetURL(_ context.Context, key string, ttl time.Duration) (string, error) {
	m.signed = append(m.signed, ttl)
	return "https://signed/" + key, nil
}
func (m *memStore) MaxSignedURLTTL() time.Duration { return m.maxTTL }
func (m *memStore) Put(_ context.Context, key, _, _ string, data []byte, public bool) (string, string, error) {
	m.objects[key] = data
	m.puts = append(m.puts, key)
	if public {
		return key, "https://cdn/" + key, nil
	}
	return key, "", nil
}

type memRepo struct{ assets map[string]domain.Asset }

func (r *memRepo) Insert(_ context.Context, a domain.Asset) error { r.assets[a.ID] = a; return nil }
func (r *memRepo) Get(_ context.Context, id string) (domain.Asset, error) {
	a, ok := r.assets[id]
	if !ok {
		return domain.Asset{}, errors.New("not found")
	}
	return a, nil
}
func (r *memRepo) Update(_ context.Context, a domain.Asset) error {
	if cur, ok := r.assets[a.ID]; ok && cur.Version != a.Version {
		return domain.ErrConflict
	}
	a.Version++
	r.assets[a.ID] = a
	return nil
}
func (r *memRepo) ListByOrg(context.Context, string, domain.Purpose, int) ([]domain.Asset, error) {
	return nil, nil
}

// SimilarImages compares hashes like the SQL query does (Hamming distance, completed images only).
func (r *memRepo) SimilarImages(_ context.Context, purposes []domain.Purpose, hash uint64, maxDistance int,
	excludeID string, limit int,
) ([]domain.SimilarImage, error) {
	out := []domain.SimilarImage{}
	for _, a := range r.assets {
		if a.ID == excludeID || a.PHash == nil || (a.Status != domain.StatusReady && a.Status != domain.StatusFlagged) ||
			!slices.Contains(purposes, a.Purpose) {
			continue
		}
		if d := domain.HashDistance(*a.PHash, hash); d <= maxDistance {
			out = append(out, domain.SimilarImage{AssetID: a.ID, OwnerOrg: a.OwnerOrg, Distance: d})
		}
	}
	slices.SortFunc(out, func(x, y domain.SimilarImage) int { return x.Distance - y.Distance })
	return out[:min(limit, len(out))], nil
}
func (r *memRepo) Ping(context.Context) error { return nil }

func whiteJPEG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, color.White)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, nil); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestUploadCompleteAndPrivateAccess(t *testing.T) {
	pub, priv := &memStore{objects: map[string][]byte{}}, &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{
		domain.PurposeProductImage: pub, domain.PurposeKYCDocument: priv,
	}, repo, inspect.New(40_000_000), domain.LinkPolicy{})
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	ctx := context.Background()

	img := whiteJPEG(t, 1200, 1200)
	a, up, err := svc.RequestUpload(ctx, seller, domain.PurposeProductImage, "dress.jpg", "image/jpeg", int64(len(img)))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Complete(ctx, seller, a.ID); !errors.Is(err, app.ErrState) {
		t.Fatalf("completing before upload must fail with ErrState, got %v", err)
	}
	pub.objects[up.Key] = img
	done, err := svc.Complete(ctx, seller, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	if done.Status != domain.StatusReady || done.Width != 1200 || done.URL == "" {
		t.Fatalf("completed asset %+v", done)
	}

	k, kup, err := svc.RequestUpload(ctx, seller, domain.PurposeKYCDocument, "ein.pdf", "application/pdf", 2000)
	if err != nil {
		t.Fatal(err)
	}
	priv.objects[kup.Key] = append([]byte("%PDF-1.7\n"), make([]byte, 1000)...)
	if _, err := svc.Complete(ctx, seller, k.ID); err != nil {
		t.Fatal(err)
	}
	other := auth.Actor{Subject: "u2", Type: "SELLER_MEMBER", OrgID: "org_other"}
	if _, err := svc.Get(ctx, other, k.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("other org must not read KYC docs, got %v", err)
	}
	got, err := svc.Get(ctx, seller, k.ID, 0)
	if err != nil || got.URL != "https://signed/"+kup.Key {
		t.Fatalf("owner should get a signed URL: %+v %v", got, err)
	}
}

func TestDisguisedFileIsRejectedAndDeleted(t *testing.T) {
	pub := &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: pub}, repo, inspect.New(40_000_000), domain.LinkPolicy{})
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	a, up, err := svc.RequestUpload(context.Background(), seller, domain.PurposeProductImage, "x.jpg", "image/jpeg", 40)
	if err != nil {
		t.Fatal(err)
	}
	pub.objects[up.Key] = []byte("<html><script>alert(1)</script></html>")
	done, err := svc.Complete(context.Background(), seller, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	if done.Status != domain.StatusRejected || len(pub.deleted) != 1 || done.URL != "" {
		t.Fatalf("expected rejection and deletion: %+v deleted=%v", done, pub.deleted)
	}
}

// delivery-runs-service verifies pre-order creatives (storefront purpose) with its own token (scope media:read),
// including ones not published yet; it may not upload, nor read private purposes, nor read without the scope.
func TestDeliveryRunsReadsCreativesWithMediaRead(t *testing.T) {
	pub, priv := &memStore{objects: map[string][]byte{}}, &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{
		domain.PurposeStorefront: pub, domain.PurposeKYCDocument: priv,
	}, repo, inspect.New(40_000_000), domain.LinkPolicy{})
	staff := auth.Actor{Subject: "stf-1", Type: "STAFF", Permissions: []string{"preorders:manage"}}
	reader := auth.Actor{Subject: domain.ClientDeliveryRunsService, Type: "SERVICE", Permissions: []string{domain.PermissionMediaRead}}
	ctx := context.Background()

	hero, _, err := svc.RequestUpload(ctx, staff, domain.PurposeStorefront, "hero.jpg", "image/jpeg", 1000)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := svc.Get(ctx, reader, hero.ID, 0); err != nil || got.ID != hero.ID {
		t.Fatalf("delivery-runs must read a pending creative: %+v %v", got, err)
	}
	noScope := reader
	noScope.Permissions = nil
	if _, err := svc.Get(ctx, noScope, hero.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("without media:read the read must be refused, got %v", err)
	}
	if _, _, err := svc.RequestUpload(ctx, reader, domain.PurposeStorefront, "x.jpg", "image/jpeg", 1000); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("a reader client must not upload, got %v", err)
	}
	kyc, _, err := svc.RequestUpload(ctx, auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"},
		domain.PurposeKYCDocument, "ein.pdf", "application/pdf", 2000)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Get(ctx, reader, kyc.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("a reader client must not read private purposes, got %v", err)
	}
	if !slices.Contains(domain.AcceptedServiceClients(), domain.ClientDeliveryRunsService) {
		t.Fatal("media must accept delivery-runs-service's own token")
	}
}
