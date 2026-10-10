package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// fakeMedia records the link lifetime the handler asks for.
type fakeMedia struct {
	app.MediaService
	ttl time.Duration
}

func (f *fakeMedia) Get(_ context.Context, _ auth.Actor, id string, ttl time.Duration) (domain.Asset, error) {
	f.ttl = ttl
	exp := time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)
	return domain.Asset{ID: id, Purpose: domain.PurposePurchaseOrder, URL: "https://signed/po.pdf", URLExpiresAt: &exp}, nil
}

func (f *fakeMedia) RequestUpload(context.Context, auth.Actor, domain.Purpose, string, string, int64) (domain.Asset, port.Upload, error) {
	return domain.Asset{}, port.Upload{}, app.ErrForbidden
}

func TestGetAssetPassesTheRequestedLinkLifetime(t *testing.T) {
	media := &fakeMedia{}
	mux := http.NewServeMux()
	actor := auth.Actor{Subject: "retail-service", Type: "SERVICE"}
	httpapi.New(media).Register(mux, func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(auth.WithActor(r.Context(), actor)))
		})
	})
	do := func(method, target string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), method, target, nil))
		return rec
	}
	post := func(target, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), http.MethodPost, target, strings.NewReader(body)))
		return rec
	}

	rec := do("GET", "/v1/assets/a1?urlTtlSeconds=1209600")
	if rec.Code != http.StatusOK || media.ttl != 14*24*time.Hour {
		t.Fatalf("status %d, ttl %s", rec.Code, media.ttl)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil || body["urlExpiresAt"] != "2030-01-01T00:00:00Z" {
		t.Fatalf("urlExpiresAt missing: %s", rec.Body.String())
	}
	if rec := do("GET", "/v1/assets/a1"); rec.Code != http.StatusOK || media.ttl != 0 {
		t.Fatalf("without urlTtlSeconds: status %d, ttl %s", rec.Code, media.ttl)
	}
	for _, bad := range []string{"0", "-5", "abc", "1.5", "99999999999"} {
		if rec := do("GET", "/v1/assets/a1?urlTtlSeconds="+bad); rec.Code != http.StatusBadRequest {
			t.Fatalf("urlTtlSeconds=%s: status %d", bad, rec.Code)
		}
	}
	if rec := do("POST", "/v1/uploads"); rec.Code != http.StatusBadRequest {
		t.Fatalf("empty body: status %d", rec.Code)
	}
	rec = post("/v1/uploads", `{"purpose":"kyc-document","fileName":"a.pdf","contentType":"application/pdf","size":10}`)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("purpose not allowed for the caller: status %d", rec.Code)
	}
}

func (f *fakeMedia) ResolveLink(_ context.Context, token string) (string, error) {
	switch token {
	case "good":
		return "https://bucket.example.test/po.pdf?X-Amz-Signature=abc", nil
	case "old":
		return "", app.ErrLinkExpired
	default:
		return "", app.ErrLinkInvalid
	}
}

func TestLongLivedLinkRedirectsWithoutAToken(t *testing.T) {
	mux := http.NewServeMux()
	httpapi.New(&fakeMedia{}).Register(mux, func(http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusUnauthorized) })
	})
	get := func(target string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), http.MethodGet, target, nil))
		return rec
	}
	rec := get("/v1/l/good")
	if rec.Code != http.StatusFound || !strings.HasPrefix(rec.Header().Get("Location"), "https://bucket.example.test/po.pdf") ||
		rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatalf("redirect: %d %v", rec.Code, rec.Header())
	}
	if rec := get("/v1/l/old"); rec.Code != http.StatusGone || !strings.Contains(rec.Body.String(), "media.link-expired") {
		t.Fatalf("expired link: %d %s", rec.Code, rec.Body.String())
	}
	if rec := get("/v1/l/forged"); rec.Code != http.StatusNotFound {
		t.Fatalf("invalid link: %d", rec.Code)
	}
	if rec := get("/v1/assets/a1"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("asset reads still need a token: %d", rec.Code)
	}
}
