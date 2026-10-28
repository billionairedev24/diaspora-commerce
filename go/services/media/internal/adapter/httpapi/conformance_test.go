package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

const contractPath = "../../../../../../contracts/openapi/media.yaml"

// TestEmbeddedContractIsIdentical keeps api/openapi.yaml (served at /openapi.yaml and /docs) equal to the contract.
func TestEmbeddedContractIsIdentical(t *testing.T) {
	want, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "openapi.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("api/openapi.yaml differs from contracts/openapi/media.yaml; copy the contract")
	}
}

// libraryMedia answers the image library operations like the service does (S310).
type libraryMedia struct {
	app.MediaService
}

var libraryTime = time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC)

func (libraryMedia) RequestLibraryUpload(_ context.Context, actor auth.Actor, fileName, contentType string, size int64) (domain.Asset, port.Upload, error) {
	if actor.OrgID == "" {
		return domain.Asset{}, port.Upload{}, app.ErrForbidden
	}
	if contentType != "image/jpeg" {
		return domain.Asset{}, port.Upload{}, domain.ErrRejected
	}
	n, _ := domain.ParseLibraryName(fileName)
	return domain.Asset{ID: "a1000000-0000-4000-8000-000000000001", OwnerOrg: actor.OrgID, UploadedBy: actor.Subject,
			Purpose: domain.PurposeProductImage, Provider: "mem", Key: "quarantine/x.jpg", FileName: fileName,
			ContentType: contentType, Bytes: size, Status: domain.StatusPending, Flags: []string{}, CreatedAt: libraryTime,
			Library: true, SKU: n.SKU, Slot: n.Slot},
		port.Upload{Method: "PUT", URL: "https://store/x", Headers: map[string]string{}, Key: "quarantine/x.jpg", ExpiresAt: libraryTime}, nil
}

func libraryImages() []domain.LibraryImage {
	ready := domain.Asset{ID: "a1000000-0000-4000-8000-000000000001", FileName: "AWD-M.MAIN.jpg", Status: domain.StatusReady,
		URL: "https://cdn/1.jpg", Width: 1600, Height: 1600, Bytes: 200_000, ContentType: "image/jpeg", CreatedAt: libraryTime,
		Library: true, SKU: "AWD-M", Slot: domain.SlotMain}
	held := domain.Asset{ID: "a1000000-0000-4000-8000-000000000002", FileName: "front.jpg", Status: domain.StatusFlagged,
		Width: 1200, Height: 1200, Bytes: 100_000, ContentType: "image/jpeg", CreatedAt: libraryTime, Library: true,
		Flags: []string{"AI review: watermark"}, Moderation: &domain.Moderation{Verdict: domain.VerdictHeld}}
	return []domain.LibraryImage{domain.ToLibraryImage(ready), domain.ToLibraryImage(held)}
}

func (libraryMedia) ListLibrary(_ context.Context, actor auth.Actor, q domain.LibraryQuery) ([]domain.LibraryImage, int, error) {
	if actor.OrgID == "" {
		return nil, 0, app.ErrForbidden
	}
	if q.Size > 200 {
		return nil, 0, domain.ErrRejected
	}
	return libraryImages(), 2, nil
}

func (libraryMedia) LookupLibrary(_ context.Context, actor auth.Actor, ownerOrg string, _, _ []string) ([]domain.LibraryImage, error) {
	if actor.Type != "SERVICE" {
		return nil, app.ErrForbidden
	}
	if ownerOrg == "" {
		return nil, domain.ErrRejected
	}
	return libraryImages(), nil
}

func (libraryMedia) RemoveFromLibrary(_ context.Context, actor auth.Actor, id string) error {
	switch {
	case id == "a1000000-0000-4000-8000-00000000dead":
		return pgrepo.ErrNotFound
	case actor.OrgID != "org_lagos":
		return app.ErrForbidden
	}
	return nil
}

// TestLibraryContractConformance drives every image library operation (and the library upload) through the real
// handlers and validates each answer, success and documented errors, against contracts/openapi/media.yaml.
func TestLibraryContractConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := doc.Validate(context.Background()); err != nil {
		t.Fatalf("contract is invalid: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://media.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	actor := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	mux := http.NewServeMux()
	httpapi.New(libraryMedia{}).Register(mux, func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(auth.WithActor(r.Context(), actor)))
		})
	})
	validated := map[string]int{}
	do := func(method, target, body string, want int) {
		t.Helper()
		var rd io.Reader
		if body != "" {
			rd = strings.NewReader(body)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://media.internal"+target, rd)
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != want {
			t.Fatalf("%s %s = %d, want %d: %s", method, target, rec.Code, want, rec.Body.String())
		}
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, target, err)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, target, rec.Code, err, rec.Body.String())
			return
		}
		validated[route.Operation.OperationID]++
	}

	do("POST", "/v1/uploads", `{"purpose":"product-image","fileName":"awd-m_pt1.jpg","contentType":"image/jpeg","size":1000,"library":true}`, 201)
	do("POST", "/v1/uploads", `{"purpose":"product-image","fileName":"x.png","contentType":"image/png","size":1000,"library":true}`, 422)
	do("POST", "/v1/uploads", `{"purpose":"storefront","fileName":"x.jpg","contentType":"image/jpeg","size":1000,"library":true}`, 422)
	do("GET", "/v1/library?q=awd&status=READY&matched=true&page=0&size=50", "", 200)
	do("GET", "/v1/library?size=500", "", 400)
	do("GET", "/v1/library?status=NOPE", "", 400)
	do("DELETE", "/v1/library/a1000000-0000-4000-8000-000000000001", "", 204)
	do("DELETE", "/v1/library/a1000000-0000-4000-8000-00000000dead", "", 404)
	actor = auth.Actor{Subject: "u2", Type: "SELLER_MEMBER", OrgID: "org_other"}
	do("DELETE", "/v1/library/a1000000-0000-4000-8000-000000000001", "", 403)
	do("POST", "/v1/library/lookup", `{"ownerOrg":"org_lagos","skus":["AWD-M"]}`, 403)
	actor = auth.Actor{Subject: domain.ClientCatalogService, Type: "SERVICE", Permissions: []string{domain.PermissionMediaImport}}
	do("POST", "/v1/library/lookup", `{"ownerOrg":"org_lagos","skus":["AWD-M"],"fileNames":["front.jpg"]}`, 200)
	do("POST", "/v1/library/lookup", `{"ownerOrg":"","skus":["AWD-M"]}`, 422)
	do("POST", "/v1/library/lookup", `not json`, 400)
	do("GET", "/v1/library", "", 403)

	for _, op := range []string{"listLibrary", "lookupLibrary", "removeFromLibrary", "requestUpload"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
