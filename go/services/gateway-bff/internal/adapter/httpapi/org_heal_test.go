package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// An approval identity missed (its seller.org.approved never arrived, or late): the seller service shows the
// application approved while identity lists no organisation. Seller Center's session asks identity to sync the
// organisation from the seller service, rotates the session's tokens (the new one carries the organisation and the
// owner role) and opens the back office — no "Start selling" dead end, no new sign-in.
func TestSellerSessionRepairsAnApprovalIdentityMissed(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	u := bff.Upstreams
	u.MemberOrgs, u.ActiveOrgID = nil, ""
	u.MemberRoles = []string{"account.customer", "account.seller_applicant"}
	u.Application = map[string]any{"id": "app-1", "reference": "APP-30200", "orgId": testkit.SellerOrgID, "kind": "SELLER", "status": "APPROVED"}
	u.SellerTruth = map[string]testkit.MemberOrg{testkit.SellerOrgID: {ID: testkit.SellerOrgID, Kind: "SELLER", Name: "Malo Branding",
		Status: "APPROVED", Role: "seller.owner"}}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	if c := u.AccessClaimsOf("user-1"); c["org"] != nil {
		t.Fatalf("before the repair the token has no organisation, got %v", c["org"])
	}
	tokens := u.CallCount("POST /oauth2/token")

	s := b.do("GET", "/v1/seller/session", nil)
	mustStatus(t, s, 200)
	body := s.json(t)
	active, _ := body["activeOrg"].(map[string]any)
	if body["approved"] != true || active == nil || active["id"] != testkit.SellerOrgID {
		t.Fatalf("session after the repair = %s", s.body)
	}
	if n := u.CallCount("POST /v1/orgs/" + testkit.SellerOrgID + "/sync"); n != 1 {
		t.Fatalf("identity sync calls = %d, want 1", n)
	}
	if u.CallCount("POST /oauth2/token") != tokens+1 {
		t.Fatalf("the session's tokens must be refreshed once after the repair")
	}

	// Repaired: the next session reads identity's copy, no further sync.
	mustStatus(t, b.do("GET", "/v1/seller/session", nil), 200)
	if n := u.CallCount("POST /v1/orgs/" + testkit.SellerOrgID + "/sync"); n != 1 {
		t.Fatalf("identity sync calls after the repair = %d, want 1", n)
	}
}

// Identity's copy still PENDING while the seller service approved it: the same repair, and the session is approved.
func TestSellerSessionRepairsAPendingCopy(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	u := bff.Upstreams
	u.MemberOrgs = []testkit.MemberOrg{{ID: testkit.SellerOrgID, Kind: "SELLER", Name: "Malo Branding", Status: "PENDING", Role: "seller.owner"}}
	u.ActiveOrgID = testkit.SellerOrgID
	u.SellerTruth = map[string]testkit.MemberOrg{testkit.SellerOrgID: {ID: testkit.SellerOrgID, Kind: "SELLER", Name: "Malo Branding",
		Status: "APPROVED", Role: "seller.owner"}}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	s := b.do("GET", "/v1/seller/session", nil)
	mustStatus(t, s, 200)
	if s.json(t)["approved"] != true {
		t.Fatalf("session = %s", s.body)
	}
	if n := u.CallCount("POST /v1/orgs/" + testkit.SellerOrgID + "/sync"); n != 1 {
		t.Fatalf("identity sync calls = %d, want 1", n)
	}
	// An approved seller whose copy is right never triggers a sync.
	bff, b = setup(t, testkit.Options{})
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("GET", "/v1/seller/session", nil), 200)
	if n := bff.Upstreams.CallCount("POST /v1/orgs/" + testkit.SellerOrgID + "/sync"); n != 0 {
		t.Fatalf("identity sync calls for an up-to-date copy = %d, want 0", n)
	}
}
