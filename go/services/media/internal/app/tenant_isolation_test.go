package app_test

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// orgRepo is memRepo with the organisation filter of the SQL repository (memRepo.ListByOrg returns nothing).
type orgRepo struct{ *memRepo }

func (r orgRepo) ListByOrg(_ context.Context, org string, p domain.Purpose, limit int) ([]domain.Asset, error) {
	var out []domain.Asset
	for _, a := range r.assets {
		if a.OwnerOrg == org && (p == "" || a.Purpose == p) {
			out = append(out, a)
		}
	}
	slices.SortFunc(out, func(x, y domain.Asset) int { return strings.Compare(x.ID, y.ID) })
	return out[:min(limit, len(out))], nil
}

func TestCrossTenantMediaAssetsKycDocumentsAndLibrary(t *testing.T) {
	w := tenanttest.NewWorld()
	pub, priv := &memStore{objects: map[string][]byte{}}, &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: pub, domain.PurposeKYCDocument: priv},
		orgRepo{repo}, inspect.New(40_000_000), domain.LinkPolicy{}, app.WithLibrary(memLibrary{repo: repo}), app.WithFetcher(neverFetch{}))
	mux := http.NewServeMux()
	httpapi.New(svc).Register(mux, w.Auth(false))
	ctx := context.Background()

	// Tenant B owns a KYC document, an image in its library and a pending product image.
	kyc, up, err := svc.RequestUpload(ctx, w.SellerB.Actor, domain.PurposeKYCDocument, "ein-b.pdf", "application/pdf", 2000)
	if err != nil {
		t.Fatal(err)
	}
	priv.objects[up.Key] = append([]byte("%PDF-1.7\n"), make([]byte, 1000)...)
	if _, err := svc.Complete(ctx, w.SellerB.Actor, kyc.ID); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(up.Key, "kyc-document/"+w.OrgB+"/") {
		t.Fatalf("object key carries the owning organisation: %s", up.Key)
	}
	lib, _, err := svc.RequestLibraryUpload(ctx, w.SellerB.Actor, "SKU-B.MAIN.jpg", "image/jpeg", 1000)
	if err != nil {
		t.Fatal(err)
	}
	markers := []string{"ein-b.pdf", "SKU-B", up.Key, "https://signed/"}

	victimReqs := []tenanttest.Request{
		tenanttest.Get("/v1/assets/" + kyc.ID),
		tenanttest.Get("/v1/assets/" + lib.ID),
		tenanttest.Send(http.MethodPost, "/v1/assets/"+kyc.ID+"/complete", ""),
		tenanttest.Send(http.MethodPost, "/v1/assets/"+lib.ID+"/complete", ""),
		tenanttest.Send(http.MethodPost, "/v1/assets/"+kyc.ID+"/review", `{"decision":"APPROVE"}`),
		tenanttest.Send(http.MethodPatch, "/v1/library/"+lib.ID, `{"fileName":"hijacked.MAIN.jpg"}`),
		tenanttest.Send(http.MethodDelete, "/v1/library/"+lib.ID, ""),
		tenanttest.Send(http.MethodPost, "/v1/library/lookup", `{"ownerOrg":"`+w.OrgB+`","skus":["SKU-B"]}`),
		tenanttest.Send(http.MethodPost, "/v1/assets/import", `{"purpose":"product-image","url":"https://x.test/a.jpg","ownerOrg":"`+w.OrgB+`"}`),
		tenanttest.Send(http.MethodPost, "/v1/assets/verify", `{"images":[{"assetId":"`+lib.ID+`","ownerOrg":"`+w.OrgB+`"}]}`),
		tenanttest.Send(http.MethodPost, "/v1/assets/rescore", `{"images":[{"assetId":"`+lib.ID+`","ownerOrg":"`+w.OrgB+`"}]}`),
		tenanttest.Get("/v1/assets/held"),
		tenanttest.Send(http.MethodPut, "/v1/image-rules", `{}`),
	}
	tenanttest.CrossTenant(t, mux, []tenanttest.Principal{w.SellerA, w.CustomerA, w.Anonymous}, victimReqs, markers...)

	// List endpoints answer 200 with the caller's own rows only.
	tenanttest.NoLeak(t, mux, w.SellerA, []tenanttest.Request{
		tenanttest.Get("/v1/assets"), tenanttest.Get("/v1/assets?purpose=kyc-document"), tenanttest.Get("/v1/library"),
		tenanttest.Get("/v1/library?q=SKU-B"),
	}, append(markers, kyc.ID, lib.ID)...)

	if repo.assets[lib.ID].FileName != "SKU-B.MAIN.jpg" || !repo.assets[lib.ID].Library {
		t.Fatalf("another organisation changed or removed the library image: %+v", repo.assets[lib.ID])
	}

	// The owner still reaches its own resources, with a short-lived link whatever TTL it asks for.
	tenanttest.Run(t, mux, []tenanttest.Case{
		{Name: "owner reads KYC", As: w.SellerB, Req: tenanttest.Get("/v1/assets/" + kyc.ID + "?urlTtlSeconds=31536000"), Expect: tenanttest.Contains("https://signed/")},
		{Name: "owner lists KYC", As: w.SellerB, Req: tenanttest.Get("/v1/assets?purpose=kyc-document"), Expect: tenanttest.Contains(kyc.ID)},
	})
	if len(priv.signed) == 0 || priv.signed[len(priv.signed)-1] > domain.DefaultLinkTTL {
		t.Fatalf("private download links are capped at the default lifetime, signed with %v", priv.signed)
	}
}

// neverFetch enables imports so the authorisation check (not the disabled feature) is what refuses the caller.
type neverFetch struct{}

func (neverFetch) Fetch(context.Context, string, int64) (port.FetchedFile, error) {
	return port.FetchedFile{}, domain.ErrRejected
}

func TestObjectKeysAreOrgPrefixedAndHaveNoPathTraversal(t *testing.T) {
	w := tenanttest.NewWorld()
	priv := &memStore{objects: map[string][]byte{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{domain.PurposeKYCDocument: priv},
		orgRepo{&memRepo{assets: map[string]domain.Asset{}}}, inspect.New(40_000_000), domain.LinkPolicy{})
	evil := w.SellerA.Actor
	evil.OrgID = "../org_b/../../x"
	for _, name := range []string{"../../org_b/ein.pdf", "a/../../b.pdf", "x.pdf/../..", `..\..\y.pdf`} {
		_, up, err := svc.RequestUpload(context.Background(), evil, domain.PurposeKYCDocument, name, "application/pdf", 100)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(up.Key, "..") || strings.Contains(up.Key, `\`) || strings.Count(up.Key, "/") != 4 {
			t.Fatalf("file %q produced key %q", name, up.Key)
		}
	}
}

func TestRouteMatrix(t *testing.T) {
	m, err := media.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "../adapter/httpapi"), string(m), ".")
}

func TestPresignedUploadLifetimeIsShort(t *testing.T) {
	w := tenanttest.NewWorld()
	priv := &memStore{objects: map[string][]byte{}}
	svc := app.NewMediaService(map[domain.Purpose]port.ObjectStore{domain.PurposeKYCDocument: priv},
		orgRepo{&memRepo{assets: map[string]domain.Asset{}}}, inspect.New(40_000_000), domain.LinkPolicy{})
	_, up, err := svc.RequestUpload(context.Background(), w.SellerA.Actor, domain.PurposeKYCDocument, "a.pdf", "application/pdf", 100)
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Until(up.ExpiresAt); d > 15*time.Minute || d <= 0 {
		t.Fatalf("upload URL lifetime %v", d)
	}
}
