package httpapi_test

import (
	"slices"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// What a plan includes decides what a seller sees and can use: bulk upload, the image library and coupons are plan
// capabilities. A plan without them does not list them in the session, drops Coupons from the navigation and refuses the
// calls (403 PLAN_FEATURE_REQUIRED); a plan with them offers everything.
func TestPlanCapabilitiesDecideWhatASellerCanUse(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{})

	bff.Upstreams.SetSellerPlan("seller_essentials")
	session := b.do("GET", "/v1/seller/session", nil).json(t)
	caps := session["capabilities"].([]any)
	if len(caps) != 1 || caps[0] != "lola" {
		t.Fatalf("essentials capabilities = %v", caps)
	}
	if slices.Contains(moduleKeys(t, session), "coupons") {
		t.Fatalf("Coupons must not be in the navigation of a plan without it: %v", moduleKeys(t, session))
	}
	for _, path := range []string{"/v1/seller/coupons", "/v1/seller/imports", "/v1/seller/image-library"} {
		r := b.do("GET", path, nil)
		mustStatus(t, r, 403)
		if got := problemCode(t, r); got != "PLAN_FEATURE_REQUIRED" {
			t.Fatalf("%s = %s, want PLAN_FEATURE_REQUIRED", path, got)
		}
	}

	// The memo lasts seconds; a plan change is read by the next resolver, so use a fresh sign-in.
	bff2, b2 := sellerSetup(t, testkit.Options{})
	bff2.Upstreams.SetSellerPlan("seller_growth")
	session = b2.do("GET", "/v1/seller/session", nil).json(t)
	for _, want := range []string{"bulkUpload", "imageLibrary", "coupons", "aiListingWriter", "lola"} {
		if !slices.Contains(toStrings(session["capabilities"]), want) {
			t.Fatalf("growth capabilities = %v, missing %s", session["capabilities"], want)
		}
	}
	if !slices.Contains(moduleKeys(t, session), "coupons") {
		t.Fatalf("Coupons is part of the growth plan: %v", moduleKeys(t, session))
	}
	if r := b2.do("GET", "/v1/seller/coupons", nil); r.status == 403 {
		t.Fatalf("a plan with coupons must reach them: %d %s", r.status, r.body)
	}
}
