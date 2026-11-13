package httpapi_test

import (
	"context"
	"net/http"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestStaffGate replays every STAFF_ONLY matrix row as a signed-in non-staff person (identity says principalType
// CUSTOMER) and as a guest: the answer is 403 NOT_STAFF / 401 and the only upstream call is identity's GET /v1/me.
func TestStaffGate(t *testing.T) {
	f := &fakePlatform{}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) {
		staff.Plans = app.NewStaffPlansService(d)
		staff.Preorders = app.NewStaffPreordersService(d)
		staff.Markets = app.NewStaffMarketsService(d)
		staff.Taxonomy = app.NewStaffTaxonomyService(d)
		staff.Tax = app.NewStaffTaxService(d)
		staff.Inventory = app.NewStaffInventoryService(d)
		staff.Identity = app.NewStaffIdentityService(d)
		staff.ContentPages = app.NewStaffContentPagesService(d)
		staff.Departments = app.NewStaffDepartmentsService(d)
	})
	enforced, unwired, denied := 0, 0, 0
	for _, r := range rowsWhere(t, func(r matrixRow) bool { return r.Class == "STAFF_ONLY" }) {
		path := fillPath(r.Pattern, testkit.Tenant{}, nil)
		var body any
		if r.Method != http.MethodGet {
			body = map[string]any{"orgId": testkit.TenantSellerOrgB}
		}
		f.mu.Lock()
		f.calls = nil
		f.mu.Unlock()
		rec := raw("customer", r.Method, path, body, "X-Org-Id", testkit.TenantSellerOrgB)
		if (rec.Code == http.StatusNotFound || rec.Code == http.StatusMethodNotAllowed) && !f.called("GET /v1/me") {
			unwired++ // route not wired in this staff harness (optional service)
			continue
		}
		enforced++
		// 403 NOT_STAFF; a few handlers validate the caller's own body first (422) before resolving the staff member,
		// which exposes nothing and calls no upstream (checked below).
		switch rec.Code {
		case http.StatusForbidden:
			denied++
		case http.StatusUnprocessableEntity, http.StatusBadRequest:
		default:
			t.Errorf("%s as non-staff: HTTP %d %s", r.key(), rec.Code, snippet(rec.Body.Bytes()))
		}
		f.mu.Lock()
		for _, c := range f.calls {
			if c != "GET /v1/me" {
				t.Errorf("%s as non-staff: upstream call %s before the staff check", r.key(), c)
			}
		}
		f.mu.Unlock()
		if g := raw("", r.Method, path, body); g.Code >= 200 && g.Code < 300 || g.Code >= 500 {
			t.Errorf("%s as guest: HTTP %d", r.key(), g.Code)
		}
	}
	t.Logf("staff rows exercised=%d (403 NOT_STAFF=%d), not wired in this harness=%d", enforced, denied, unwired)
	if enforced < 150 || denied < 120 {
		t.Fatalf("only %d staff routes exercised", enforced)
	}
}

// TestActiveOrgSwitchIsMembershipChecked: tenant A cannot select tenant B's organisation (nothing is forwarded to
// identity), and a person removed from an organisation loses the seller routes at once, although the access token they
// hold still carries the old org claim.
func TestActiveOrgSwitchIsMembershipChecked(t *testing.T) {
	w := testkit.NewTenantWorld(t)
	mark := w.Mark()
	status, body := w.Do(w.SellerA, testkit.Call{Method: "PUT", Path: "/v1/seller/session/active-org", Body: map[string]any{"orgId": testkit.TenantSellerOrgB}})
	if status != http.StatusForbidden {
		t.Fatalf("switching to another tenant's org: HTTP %d %s", status, snippet(body))
	}
	for _, r := range w.Since(mark) {
		if r.Method == "PUT" && r.Path == "/v1/me/active-org" {
			t.Fatal("select was forwarded to identity for an org the caller is not a member of")
		}
	}
	if got := w.ActiveOrgOf(w.SellerA.Sub); got != testkit.TenantSellerOrgA {
		t.Fatalf("active org moved to %s", got)
	}
	w.Leave(w.SellerA.Sub, testkit.TenantSellerOrgA)
	mark = w.Mark()
	status, body = w.Do(w.SellerA, testkit.Call{Method: "GET", Path: "/v1/seller/orders"})
	if status != http.StatusForbidden || !strings.Contains(string(body), "NO_ORGANISATION") {
		t.Fatalf("after removal: HTTP %d %s", status, snippet(body))
	}
	for _, r := range w.Since(mark) {
		if r.Path != "/v1/me" {
			t.Fatalf("after removal the BFF still called %s %s", r.Method, r.Path)
		}
	}
}

// TestSwitchingOrgRotatesTheSessionTokens: identity stamps the org claim at issuance, so a web session whose active
// org changed must get new tokens at once; otherwise the owning services keep seeing the previous org for up to the
// access-token lifetime while the BFF (reading identity's live active org) already shows the new one.
func TestSwitchingOrgRotatesTheSessionTokens(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{SellerPending: true})
	orgClaim := func() string {
		id, _, _ := strings.Cut(b.cookie, ".")
		sess, err := bff.Store.Load(context.Background(), "web:"+id)
		if err != nil || sess == nil || sess.Tokens == nil {
			t.Fatalf("session: %v %v", sess, err)
		}
		return testkit.ParseBearerOrg(sess.Tokens.AccessToken)
	}
	if got := orgClaim(); got != testkit.SellerOrgID {
		t.Fatalf("signed in with org claim %q", got)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID}), 200)
	if got := orgClaim(); got != testkit.ProviderOrgID {
		t.Fatalf("after switching to %s the session's access token still carries org %q", testkit.ProviderOrgID, got)
	}
}
