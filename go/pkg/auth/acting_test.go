package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
)

// sellerOwner is a person who signed up as a customer and whose seller application was approved: identity's token
// then carries pt SELLER_MEMBER and the organisation.
func sellerOwner() Actor {
	return Actor{Subject: "user-1", Type: "SELLER_MEMBER", OrgID: "org_malo",
		Roles: []string{"account.customer", "account.seller_applicant", "seller.owner"}, Permissions: []string{"catalog:write", "mcp:shopper"}}
}

func TestActingAsCustomerMakesAMemberShopAsThemselves(t *testing.T) {
	a := ActingAs(sellerOwner(), "CUSTOMER")
	if a.Subject != "user-1" || a.Type != "CUSTOMER" || a.OrgID != "" {
		t.Fatalf("got %+v, want the person as a customer without the organisation", a)
	}
	if !slices.Equal(a.Roles, []string{"account.customer", "account.seller_applicant"}) {
		t.Fatalf("roles %v: want only the account roles", a.Roles)
	}
	if !a.Can("mcp:shopper") {
		t.Fatalf("permissions %v: want the token's own", a.Permissions)
	}
	if lower := ActingAs(sellerOwner(), " customer "); lower.Type != "CUSTOMER" {
		t.Fatalf("header values are case-insensitive: got %+v", lower)
	}
	for _, pt := range []string{"PROVIDER_MEMBER", "DRIVER", "CUSTOMER"} {
		p := sellerOwner()
		p.Type = pt
		if got := ActingAs(p, "CUSTOMER"); got.Type != "CUSTOMER" || got.OrgID != "" {
			t.Errorf("%s: got %+v", pt, got)
		}
	}
}

func TestActingAsNeverChangesStaffServicesOrMissingContext(t *testing.T) {
	member := sellerOwner()
	if got := ActingAs(member, ""); got.Type != "SELLER_MEMBER" || got.OrgID != "org_malo" {
		t.Fatalf("no header: Seller Center keeps the organisation, got %+v", got)
	}
	if got := ActingAs(member, "SELLER_MEMBER"); got.Type != "SELLER_MEMBER" {
		t.Fatalf("unknown context: got %+v", got)
	}
	for _, a := range []Actor{
		{Subject: "s1", Type: "STAFF", Roles: []string{"platform.ops_manager"}, Permissions: []string{"orders:read"}},
		{Subject: "orders-service", Type: "SERVICE", Permissions: []string{"cart:service"}},
		{Subject: "agent", Type: "AI_AGENT"},
		LocalSuperAdmin(),
		{Type: "SELLER_MEMBER", OrgID: "org_malo"},
	} {
		if got := ActingAs(a, "CUSTOMER"); got.Type != a.Type || got.OrgID != a.OrgID {
			t.Errorf("%+v became %+v", a, got)
		}
	}
}

type stubVerifier struct{ actor Actor }

func (s stubVerifier) Verify(context.Context, string) (Actor, error) { return s.actor, nil }

func TestMiddlewareAppliesTheActingContext(t *testing.T) {
	var got Actor
	capture := http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { got, _ = FromContext(r.Context()) })
	serve := func(h http.Handler, header string) Actor {
		req := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/v1/customers/me/payment-methods", nil)
		req.Header.Set("Authorization", "Bearer "+unsigned(`{"sub":"user-1","pt":"SELLER_MEMBER","org":"org_malo","roles":["account.customer","seller.owner"]}`))
		if header != "" {
			req.Header.Set(ActingAsHeader, header)
		}
		got = Actor{}
		h.ServeHTTP(httptest.NewRecorder(), req)
		return got
	}
	verified := Middleware(stubVerifier{actor: sellerOwner()}, false)(capture)
	if a := serve(verified, "CUSTOMER"); a.Type != "CUSTOMER" || a.OrgID != "" || a.Subject != "user-1" {
		t.Fatalf("verified token acting as customer: got %+v", a)
	}
	if a := serve(verified, ""); a.Type != "SELLER_MEMBER" || a.OrgID != "org_malo" {
		t.Fatalf("verified token without the header: got %+v", a)
	}
	local := Middleware(nil, false)(capture)
	if a := serve(local, "CUSTOMER"); a.Type != "CUSTOMER" || a.OrgID != "" || a.Subject != "user-1" {
		t.Fatalf("local profile acting as customer: got %+v", a)
	}
	if a := serve(local, ""); a.Type != "SELLER_MEMBER" || a.OrgID != "org_malo" {
		t.Fatalf("local profile without the header: got %+v", a)
	}
}
