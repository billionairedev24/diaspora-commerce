package httpapi_test

import (
	"fmt"
	"net/url"
	"slices"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestStartSellingCreatesThePrefilledApplicationOnce covers S286: a signed-in customer with no organisation starts
// selling; the applicant role is added, the application is prefilled from the account (name, email, phone, default
// address) and a second start resumes it. Until approval the session shows the DRAFT organisation (onboarding only).
func TestStartSellingCreatesThePrefilledApplicationOnce(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{SellerPending: true})
	up := bff.Upstreams
	up.MemberOrgs, up.ActiveOrgID, up.NoApplication = nil, "", true
	up.DefaultAddresses = []map[string]any{
		{"id": "a1", "fullName": "Adaeze Nwosu", "line1": "9 Old Spanish Trl", "city": "Houston", "state": "TX", "postalCode": "77054", "isDefault": false},
		{"id": "a2", "fullName": "Adaeze Nwosu", "line1": "4410 Bellaire Blvd", "line2": "Apt 2", "city": "Houston", "state": "TX", "postalCode": "77025", "isDefault": true},
	}

	before := b.do("GET", "/v1/seller/session", nil).json(t)
	if before["activeOrg"] != nil {
		t.Fatalf("customer without an application has an active org: %v", before)
	}

	r := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"})
	if r.status != 201 {
		t.Fatalf("start = %d %s", r.status, r.body)
	}
	if !slices.Equal(up.ApplicantRoles, []string{"seller-applicant"}) {
		t.Fatalf("applicant roles = %v", up.ApplicantRoles)
	}
	pre := up.LastPrefill
	addr, _ := pre["address"].(map[string]any)
	if pre["phone"] != "+17135550142" || pre["givenName"] != "Adaeze" || pre["familyName"] != "Nwosu" || pre["email"] != "adaeze@example.com" || addr["line1"] != "4410 Bellaire Blvd" || addr["line2"] != "Apt 2" || addr["country"] != "US" {
		t.Fatalf("prefill = %v", pre)
	}
	var labels []string
	for _, st := range r.json(t)["steps"].([]any) {
		labels = append(labels, st.(map[string]any)["key"].(string)+"="+st.(map[string]any)["label"].(string))
	}
	if fmt.Sprint(labels) != "[BUSINESS=Your business BUSINESS_DETAILS=Business details & TIN OWNERS=Owners STRIPE_VERIFICATION=Stripe verification "+
		"PAYOUTS=Payouts PLAN=Plan CATALOG_COMPLIANCE=Catalog, permits & licences REVIEW=AfroTransact staff review]" {
		t.Fatalf("steps = %v", labels)
	}
	form := r.json(t)["form"].(map[string]any)
	details := form["details"].(map[string]any)
	business := form["business"].(map[string]any)
	if details["legalFirstName"] != "Adaeze" || business["contactEmail"] != "adaeze@example.com" || business["phone"] != "+17135550142" ||
		details["address"].(map[string]any)["postalCode"] != "77025" || form["editable"] != true {
		t.Fatalf("form = %v", form)
	}
	want := []any{"business.contactEmail", "business.phone", "details.address", "details.legalFirstName", "details.legalLastName"}
	if fmt.Sprint(form["prefilled"]) != fmt.Sprint(want) {
		t.Fatalf("prefilled = %v", form["prefilled"])
	}

	// Idempotent: the second click resumes the same application.
	again := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"})
	if again.status != 200 || again.json(t)["reference"] != "APP-40001" {
		t.Fatalf("resume = %d %s", again.status, again.body)
	}

	// The session now shows the DRAFT organisation with Onboarding only.
	s := b.do("GET", "/v1/seller/session", nil).json(t)
	org, _ := s["activeOrg"].(map[string]any)
	if org == nil || org["id"] != "org_new" || org["status"] != "DRAFT" || org["roleKey"] != "seller.owner" || s["approved"] != false ||
		s["landing"] != "onboarding" || fmt.Sprint(moduleKeys(t, s)) != "[onboarding]" {
		t.Fatalf("applicant session = %v", s)
	}

	// The other kind conflicts with the application in progress.
	if c := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "PROVIDER"}); c.status != 409 || c.json(t)["code"] != "APPLICATION_EXISTS" {
		t.Fatalf("other kind = %d %s", c.status, c.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "DRIVER"}), 422)

	// Autosave of the first two steps; the seller service's field errors come back as field errors.
	bad := b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"storeName": "x"})
	if bad.status != 422 || bad.json(t)["errors"].(map[string]any)["storeName"] == nil {
		t.Fatalf("invalid business = %d %s", bad.status, bad.body)
	}
	saved := b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"offering": "products", "entityType": "SOLE_PROPRIETOR", "country": "us",
		"storeName": "Ada's Pantry", "contactEmail": "adaeze@example.com", "phone": "+17135550142"})
	if saved.status != 200 {
		t.Fatalf("business = %d %s", saved.status, saved.body)
	}
	sb := saved.json(t)["form"].(map[string]any)["business"].(map[string]any)
	if sb["entityType"] != "SOLE_PROPRIETOR" || sb["offering"] != "PRODUCTS" || sb["country"] != "US" {
		t.Fatalf("saved business = %v", sb)
	}
	up.Application["businessDetails"].(map[string]any)["dateOfBirth"] = "1990-04-02"
	det := b.do("PUT", "/v1/seller/onboarding/business-details", map[string]any{"legalFirstName": "Adaeze", "legalLastName": "Nwosu",
		"address": map[string]any{"line1": "1 Main St", "city": "Houston", "state": "tx", "postalCode": "77002"}})
	if det.status != 200 {
		t.Fatalf("details = %d %s", det.status, det.body)
	}
	stored := up.Application["businessDetails"].(map[string]any)
	if stored["dateOfBirth"] != "1990-04-02" || stored["address"].(map[string]any)["state"] != "TX" {
		t.Fatalf("details sent to seller = %v", stored)
	}
}

// TestStartSellingRefusesStaffAndApprovedSellers: staff accounts use a personal account; an approved seller opens
// Seller Center instead of applying again.
func TestStartSellingRefusesStaffAndApprovedSellers(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{SellerPending: true})
	if r := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"}); r.status != 409 || r.json(t)["code"] != "ALREADY_APPROVED" {
		t.Fatalf("approved seller = %d %s", r.status, r.body)
	}
	bff.Upstreams.MemberOrgs = nil
	bff.Upstreams.MemberRoles = []string{"account.customer", "platform.customer_support"}
	if r := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "PROVIDER"}); r.status != 403 || r.json(t)["code"] != "STAFF_CANNOT_APPLY" {
		t.Fatalf("staff = %d %s", r.status, r.body)
	}
	if len(bff.Upstreams.ApplicantRoles) != 0 || bff.Upstreams.Application != nil {
		t.Fatalf("refused starts changed state: roles %v app %v", bff.Upstreams.ApplicantRoles, bff.Upstreams.Application)
	}
}

// TestLoginPassesTheSellerIntentToIdentity: Start selling for a guest opens identity's sign-up page with the
// seller copy (intent=sell); unknown intents are dropped.
func TestLoginPassesTheSellerIntentToIdentity(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	b.origin = testkit.SellerCenterOrigin
	r := b.do("GET", "/v1/auth/login?prompt=create&intent=sell&returnTo="+testkit.SellerCenterOrigin+"%2Fonboarding%3Frole%3Dseller", nil)
	loc := r.header.Get("Location")
	if r.status != 302 || !containsAll(loc, "prompt=create", "intent=sell", "redirect_uri="+urlEnc(testkit.SellerCenterOrigin)) {
		t.Fatalf("login = %d %s", r.status, loc)
	}
	if r := b.do("GET", "/v1/auth/login?intent=admin", nil); containsAll(r.header.Get("Location"), "intent=") {
		t.Fatalf("unknown intent passed through: %s", r.header.Get("Location"))
	}
}

func containsAll(s string, parts ...string) bool {
	for _, p := range parts {
		if !strings.Contains(s, p) {
			return false
		}
	}
	return true
}

func urlEnc(s string) string { return url.QueryEscape(s) }
