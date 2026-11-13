package httpapi_test

import (
	"net/http"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/httpapi"
)

// TestPromiseSnapshotIdsMustHaveTheIssuedShape: GET /v1/promises/{id} is a token link, so only the exact shape of an
// issued id reaches the store (no wildcard, path or key-pattern probing of the snapshot store).
func TestPromiseSnapshotIdsMustHaveTheIssuedShape(t *testing.T) {
	w := tenanttest.NewWorld()
	mux := http.NewServeMux()
	httpapi.New(&fakeService{}).Register(mux, w.Auth(true))
	for _, id := range []string{"prm_*", "prm_aaaaaaaaaaaaaaaaaaaaaaaa%2f..", "prm_AAAAAAAAAAAAAAAAAAAAAAAA", "*", "prm_aaaaaaaaaaaaaaaaaaaaaaa", "prm_aaaaaaaaaaaaaaaaaaaaaaaaa"} {
		res := tenanttest.Do(mux, w.Anonymous, tenanttest.Get("/v1/promises/"+id))
		if res.Status != http.StatusNotFound {
			t.Fatalf("id %q: status %d", id, res.Status)
		}
	}
	tenanttest.Run(t, mux, []tenanttest.Case{
		{Name: "issued id", As: w.Anonymous, Req: tenanttest.Get("/v1/promises/prm_aaaaaaaaaaaaaaaaaaaaaaaa"), Expect: tenanttest.Allowed()},
	})
}

func TestRouteMatrix(t *testing.T) {
	m, err := promise.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "."), string(m), ".")
}
