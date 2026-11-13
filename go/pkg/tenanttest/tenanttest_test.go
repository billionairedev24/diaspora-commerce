package tenanttest_test

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
)

// owned is a tiny service: GET /v1/things/{id} returns the thing only to the org that owns it.
func owned(w tenanttest.World) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /v1/things/{id}", w.Auth(false)(http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		a, _ := auth.FromContext(r.Context())
		if r.PathValue("id") != "thing-of-"+a.OrgID {
			rw.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = rw.Write([]byte(`{"id":"` + r.PathValue("id") + `"}`))
	})))
	return mux
}

func TestWorldMintsDistinctPrincipalsBehindTheRealMiddleware(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.SellerPerms("things:read"))
	h := owned(w)
	tenanttest.Run(t, h, []tenanttest.Case{
		{Name: "owner reads", As: w.SellerA, Req: tenanttest.Get("/v1/things/thing-of-" + w.OrgA), Expect: tenanttest.Allowed()},
		{Name: "other org refused", As: w.SellerB, Req: tenanttest.Get("/v1/things/thing-of-" + w.OrgA), Expect: tenanttest.Denied()},
		{Name: "anonymous refused", As: w.Anonymous, Req: tenanttest.Get("/v1/things/thing-of-" + w.OrgA), Expect: tenanttest.Status(http.StatusUnauthorized)},
	})
	if got := w.SellerA.Actor; got.OrgID != w.OrgA || got.Type != "SELLER_MEMBER" || len(got.Permissions) != 1 {
		t.Fatalf("seller a: %+v", got)
	}
	tenanttest.CrossTenant(t, h, []tenanttest.Principal{w.SellerB, w.CustomerA, w.CustomerB},
		[]tenanttest.Request{tenanttest.Get("/v1/things/thing-of-" + w.OrgA)}, "thing-of-"+w.OrgA)
}

func TestUnknownTokenIsUnauthenticated(t *testing.T) {
	w := tenanttest.NewWorld()
	res := tenanttest.Do(owned(w), tenanttest.Principal{Name: "forged"}, tenanttest.Get("/v1/things/x"))
	if res.Status != http.StatusUnauthorized {
		t.Fatalf("status %d", res.Status)
	}
}

type recorder struct {
	testing.TB
	errors []string
}

func (r *recorder) Errorf(format string, args ...any) {
	r.errors = append(r.errors, sprintf(format, args...))
}
func (r *recorder) Fatalf(format string, args ...any) {
	r.errors = append(r.errors, sprintf(format, args...))
}
func (r *recorder) Helper() {}

func sprintf(format string, args ...any) string { return fmt.Sprintf(format, args...) }

func TestRouteDriftGuard(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "x_test.go"), []byte("package x\n\nfunc TestCrossTenant(t *testing.T) {}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	patterns := []string{"GET /v1/things/{id}", "POST /v1/things"}
	good := "# comment\nGET /v1/things/{id} | ORG_OWNED | owner org checked | TestCrossTenant\n" +
		"POST /v1/things | ORG_OWNED | org from claim | TestCrossTenant/create\nEVENT topic.x | SERVICE_ONLY | consumer | n/a\n"
	r := &recorder{TB: t}
	tenanttest.CheckRoutes(r, patterns, good, dir)
	if len(r.errors) != 0 {
		t.Fatalf("complete matrix flagged: %v", r.errors)
	}

	cases := map[string]string{
		"missing":   "GET /v1/things/{id} | ORG_OWNED | e | TestCrossTenant\n",
		"stale":     good + "DELETE /v1/gone | ORG_OWNED | e | TestCrossTenant\n",
		"no test":   strings.Replace(good, "TestCrossTenant/create", "TestNope", 1),
		"na org":    strings.Replace(good, "TestCrossTenant/create", "n/a", 1),
		"bad class": strings.Replace(good, "ORG_OWNED", "WHATEVER", 1),
	}
	for name, matrix := range cases {
		t.Run(name, func(t *testing.T) {
			rec := &recorder{TB: t}
			tenanttest.CheckRoutes(rec, patterns, matrix, dir)
			if len(rec.errors) == 0 {
				t.Fatalf("drift not detected")
			}
		})
	}
}

func TestPatternsFromSourceFindsRegisteredRoutes(t *testing.T) {
	dir := t.TempDir()
	src := "package h\n\nfunc r(m interface{ Handle(string, any) }) {\n\tm.Handle(\"GET /v1/a/{id}\", nil)\n\tm.Handle(\"POST /v1/b\", nil)\n\t_ = \"not a route\"\n}\n"
	if err := os.WriteFile(filepath.Join(dir, "h.go"), []byte(src), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "h_test.go"), []byte("package h\n\nvar _ = \"GET /ignored\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	got := tenanttest.PatternsFromSource(t, dir)
	if strings.Join(got, ",") != "GET /v1/a/{id},POST /v1/b" {
		t.Fatalf("patterns %v", got)
	}
}
