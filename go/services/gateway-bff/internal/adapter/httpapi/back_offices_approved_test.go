package httpapi_test

import (
	"fmt"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// An approved seller whose approval identity has not mirrored yet (its organisation still PENDING there, the applicant
// role kept): the account menu opens Seller Center, not "Continue your seller application" (onboarding), because the
// seller service already approved the application. The lookup relays the person's own token, not the shopper context.
func TestMeBackOfficesOpenSellerCenterOnceTheApplicationIsApproved(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	u := bff.Upstreams
	u.MemberOrgs = []testkit.MemberOrg{{ID: testkit.PendingOrgID, Kind: "SELLER", Name: "Malo Branding", Status: "PENDING", Role: "seller.owner"}}
	u.ActiveOrgID = testkit.PendingOrgID
	u.MemberRoles = []string{"account.customer", "account.seller_applicant"}
	u.Application = map[string]any{"id": "app-1", "reference": "APP-30200", "orgId": testkit.PendingOrgID, "kind": "SELLER", "status": "APPROVED"}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	user := b.do("GET", "/v1/me", nil).json(t)["user"].(map[string]any)
	if got := fmt.Sprint(user["backOffices"]); got != "[map[app:seller-center state:active]]" {
		t.Fatalf("approved application, identity still PENDING: back offices = %s", got)
	}
	if v, ok := u.ActingAsSent("GET /v1/applications/me"); !ok || v != "" {
		t.Fatalf("the application lookup must relay the person's own token, got X-Acting-As %q (sent %v)", v, ok)
	}

	// Still in review: the menu keeps continuing the application.
	bff, b = setup(t, testkit.Options{})
	bff.Upstreams.MemberOrgs = []testkit.MemberOrg{{ID: testkit.PendingOrgID, Kind: "SELLER", Name: "Malo Branding", Status: "PENDING", Role: "seller.owner"}}
	bff.Upstreams.ActiveOrgID = testkit.PendingOrgID
	bff.Upstreams.Application = map[string]any{"id": "app-1", "reference": "APP-30200", "orgId": testkit.PendingOrgID, "kind": "SELLER", "status": "PENDING_REVIEW"}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	user = b.do("GET", "/v1/me", nil).json(t)["user"].(map[string]any)
	if got := fmt.Sprint(user["backOffices"]); got != "[map[app:seller-center state:application]]" {
		t.Fatalf("application in review: back offices = %s", got)
	}
}
