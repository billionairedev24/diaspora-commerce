package auth

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
)

func unsigned(claims string) string {
	enc := base64.RawURLEncoding
	return enc.EncodeToString([]byte(`{"alg":"RS256","kid":"k1"}`)) + "." + enc.EncodeToString([]byte(claims)) + ".c2ln"
}

func TestLocalActorIsTheSuperAdminWithoutAPersonalToken(t *testing.T) {
	for name, h := range map[string]string{
		"none":    "",
		"basic":   "Basic Zm9vOmJhcg==",
		"garbage": "Bearer not-a-jwt",
		"staff":   "Bearer " + unsigned(`{"sub":"s1","pt":"STAFF","perms":["orders:read"]}`),
		"service": "Bearer " + unsigned(`{"sub":"cart-service","pt":"SERVICE","scope":"cart:service"}`),
		"no sub":  "Bearer " + unsigned(`{"pt":"CUSTOMER"}`),
	} {
		a := LocalActor(h)
		if a.Subject != "local-dev" || !a.Can("orders:manage") {
			t.Errorf("%s: got %+v, want the local super admin", name, a)
		}
	}
}

func TestLocalActorCustomerActsAsThemselves(t *testing.T) {
	a := LocalActor("Bearer " + unsigned(`{"sub":"cust-1","pt":"CUSTOMER","roles":["account.customer"],"perms":["mcp:shopper","*"],"scope":["openid"]}`))
	if a.Subject != "cust-1" || a.Type != "CUSTOMER" {
		t.Fatalf("got %+v", a)
	}
	if a.Can("orders:manage") || !a.Can("mcp:shopper") || !slices.Contains(a.Permissions, "openid") {
		t.Fatalf("permissions %v: want the token's own, never the wildcard", a.Permissions)
	}
}

func TestLocalActorDriverWithStringScope(t *testing.T) {
	a := LocalActor("bearer " + unsigned(`{"sub":"drv-7","pt":"driver","perms":["delivery:operate"],"scope":"openid profile"}`))
	if a.Subject != "drv-7" || a.Type != "DRIVER" || !a.Can("delivery:operate") || !a.Can("profile") {
		t.Fatalf("got %+v", a)
	}
}

func TestMiddlewareWithoutVerifierUsesLocalActor(t *testing.T) {
	var got Actor
	h := Middleware(nil, false)(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		got, _ = FromContext(r.Context())
	}))
	req := httptest.NewRequest(http.MethodGet, "/v1/cart", nil)
	req.Header.Set("Authorization", "Bearer "+unsigned(`{"sub":"cust-9","pt":"CUSTOMER"}`))
	h.ServeHTTP(httptest.NewRecorder(), req)
	if got.Subject != "cust-9" {
		t.Fatalf("customer token: got %+v", got)
	}
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/v1/cart", nil))
	if got.Subject != "local-dev" {
		t.Fatalf("no token: got %+v", got)
	}
}

func TestLocalActorSellerMemberActsForTheirOrganisation(t *testing.T) {
	a := LocalActor("Bearer " + unsigned(`{"sub":"u1","pt":"SELLER_MEMBER","org":"org_x","perms":["orders:update-status"]}`))
	if a.Type != "SELLER_MEMBER" || a.OrgID != "org_x" || !a.Can("orders:update-status") || a.Can("orders:manage") {
		t.Fatalf("got %+v", a)
	}
}
