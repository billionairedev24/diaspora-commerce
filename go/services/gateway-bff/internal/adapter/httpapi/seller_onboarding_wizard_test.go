package httpapi_test

import (
	"fmt"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// startedApplicant is a customer who started a seller application (S286) on a BFF whose Stripe key is key.
func startedApplicant(t *testing.T, key string) (*testkit.Upstreams, *browser) {
	t.Helper()
	bff, b := sellerSetup(t, testkit.Options{SellerPending: true, SellerPublishableKey: key, Content: true})
	up := bff.Upstreams
	up.MemberOrgs, up.ActiveOrgID, up.NoApplication = nil, "", true
	if r := b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"}); r.status != 201 {
		t.Fatalf("start = %d %s", r.status, r.body)
	}
	return up, b
}

func steps(t *testing.T, st map[string]any) []string {
	t.Helper()
	var out []string
	for _, s := range st["steps"].([]any) {
		m := s.(map[string]any)
		out = append(out, fmt.Sprintf("%s:%s=%s", m["slug"], m["label"], m["state"]))
	}
	return out
}

// TestOnboardingWizardResumesAtTheFirstIncompleteStep: every step has a URL slug and the design's title; the
// application resumes at the first incomplete step; a sole proprietor's second step is "About you" without Owners.
func TestOnboardingWizardResumesAtTheFirstIncompleteStep(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_x")
	st := b.do("GET", "/v1/seller/onboarding", nil).json(t)
	if st["resumeStep"] != "business" || st["stripe"].(map[string]any)["status"] != "NOT_STARTED" {
		t.Fatalf("fresh application = %v / %v", st["resumeStep"], st["stripe"])
	}
	saved := b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"offering": "PRODUCTS", "entityType": "SOLE_PROPRIETOR", "country": "US",
		"storeName": "Ada's Pantry", "contactEmail": "adaeze@example.com", "phone": "+17135550142"}).json(t)
	got := strings.Join(steps(t, saved), " | ")
	want := "business:Your business=DONE | business-details:About you=TODO | stripe-verification:Stripe verification=TODO | payouts:Payouts=TODO | " +
		"plan:Plan=TODO | catalog-compliance:Catalog, permits & licences=TODO | review:Review & submit=TODO"
	if got != want || saved["resumeStep"] != "business-details" {
		t.Fatalf("steps = %s (resume %v)", got, saved["resumeStep"])
	}
	// A prefilled payout holder name (seller service) is not a completed Payouts step.
	up.Application["payouts"] = map[string]any{"accountHolderName": "Adaeze Nwosu", "externalAccountLinked": false}
	st = b.do("GET", "/v1/seller/onboarding", nil).json(t)
	if p := st["form"].(map[string]any)["payouts"].(map[string]any); p["linked"] != false || p["accountHolderName"] != "Adaeze Nwosu" {
		t.Fatalf("payouts = %v", p)
	}
	for _, s := range steps(t, st) {
		if strings.HasPrefix(s, "payouts:") && !strings.HasSuffix(s, "=TODO") {
			t.Fatalf("payouts step = %s", s)
		}
	}
}

// TestOnboardingDetailsSendTheTaxIDOnceAndShowItMasked: the TIN reaches the seller service (which encrypts it) and
// only its masked last 4 come back.
func TestOnboardingDetailsSendTheTaxIDOnceAndShowItMasked(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_x")
	r := b.do("PUT", "/v1/seller/onboarding/business-details", map[string]any{"legalFirstName": "Adaeze", "legalLastName": "Nwosu", "taxId": "123-45-6789",
		"dateOfBirth": "1990-04-02", "address": map[string]any{"line1": "1 Main St", "city": "Houston", "state": "tx", "postalCode": "77002"}})
	if r.status != 200 {
		t.Fatalf("details = %d %s", r.status, r.body)
	}
	if up.StepBodies["business-details"]["taxId"] != "123-45-6789" {
		t.Fatalf("seller got %v", up.StepBodies["business-details"])
	}
	if strings.Contains(string(r.body), "123-45-6789") || strings.Contains(string(r.body), "123456789") {
		t.Fatalf("tax ID echoed: %s", r.body)
	}
	d := r.json(t)["form"].(map[string]any)["details"].(map[string]any)
	if d["taxIdMasked"] != "•••-••-6789" || d["dateOfBirth"] != "1990-04-02" || d["address"].(map[string]any)["state"] != "TX" {
		t.Fatalf("details = %v", d)
	}
}

// TestOnboardingOwnersPayoutsPlanAndCompliance: the remaining steps go to the seller service as its step bodies.
func TestOnboardingOwnersPayoutsPlanAndCompliance(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_x")
	owners := b.do("PUT", "/v1/seller/onboarding/owners", map[string]any{"owners": []map[string]any{
		{"firstName": "Kofi", "lastName": "Mensah", "title": "CEO", "email": "kofi@example.com", "ownershipPercent": 60, "significantControl": true,
			"dateOfBirth": "1980-02-02", "address": map[string]any{"line1": "9800 Westheimer Rd", "city": "Houston", "state": "tx", "postalCode": "77042"}},
		{"firstName": "Ama", "lastName": "Mensah", "title": "COO", "ownershipPercent": 40}}})
	if owners.status != 200 {
		t.Fatalf("owners = %d %s", owners.status, owners.body)
	}
	sent := up.StepBodies["owners"]["owners"].([]any)
	first := sent[0].(map[string]any)
	if len(sent) != 2 || first["address"].(map[string]any)["state"] != "TX" || first["ownershipPercent"] != 60.0 || sent[1].(map[string]any)["address"] != nil {
		t.Fatalf("owners sent = %v", sent)
	}
	if o := owners.json(t)["form"].(map[string]any)["owners"].([]any); len(o) != 2 || o[0].(map[string]any)["title"] != "CEO" {
		t.Fatalf("owners form = %v", o)
	}

	payouts := b.do("PUT", "/v1/seller/onboarding/payouts", map[string]any{"accountHolderName": "Kofi Mensah", "accountType": "Checking",
		"routingNumber": "021 000 021", "accountNumber": "0001-2345-6789", "accountNumberConfirmation": "000123456789"})
	if payouts.status != 200 {
		t.Fatalf("payouts = %d %s", payouts.status, payouts.body)
	}
	if s := up.StepBodies["payouts"]; s["routingNumber"] != "021000021" || s["accountNumber"] != "000123456789" || s["accountType"] != "checking" {
		t.Fatalf("payouts sent = %v", s)
	}
	if strings.Contains(string(payouts.body), "000123456789") {
		t.Fatalf("account number echoed: %s", payouts.body)
	}
	if p := payouts.json(t)["form"].(map[string]any)["payouts"].(map[string]any); p["linked"] != true || p["accountLast4"] != "6789" {
		t.Fatalf("payouts form = %v", p)
	}
	if bad := b.do("PUT", "/v1/seller/onboarding/payouts", map[string]any{"accountHolderName": "Kofi"}); bad.status != 422 ||
		bad.json(t)["errors"].(map[string]any)["payouts.accountNumber"] == nil {
		t.Fatalf("invalid payouts = %d %s", bad.status, bad.body)
	}

	plan := b.do("PUT", "/v1/seller/onboarding/plan", map[string]any{"planCode": "seller_growth", "cadence": "monthly", "paymentMethodKind": "card",
		"stripePaymentMethodId": "pm_1", "cardBrand": "Visa", "cardLast4": "4242", "cardExpMonth": 8, "cardExpYear": 2029})
	if s := up.StepBodies["plan"]; plan.status != 200 || s["cadence"] != "MONTHLY" || s["paymentMethodKind"] != "CARD" || s["cardBrand"] != "visa" {
		t.Fatalf("plan = %d %v", plan.status, s)
	}
	if fc := plan.json(t)["form"].(map[string]any)["plan"].(map[string]any)["firstCharge"].(map[string]any); !strings.HasPrefix(fc["sentence"].(string), "Free for 30 days") {
		t.Fatalf("first charge = %v", fc)
	}

	comp := b.do("PUT", "/v1/seller/onboarding/catalog-compliance", map[string]any{"categories": []string{"Groceries", "prepared_food", "groceries"},
		"foodPermit":         map[string]any{"permitType": "cottage_food", "permitNumber": "CF-1", "expiresOn": "2027-06-30", "documentAssetId": "asset-1"},
		"agreementsAccepted": true, "w9Certified": true, "w9SignerName": "Kofi Mensah"})
	s := up.StepBodies["catalog-compliance"]
	if comp.status != 200 || fmt.Sprint(s["categories"]) != "[groceries prepared_food]" || s["foodPermit"].(map[string]any)["permitType"] != "COTTAGE_FOOD" {
		t.Fatalf("compliance = %d %v", comp.status, s)
	}
	// The accepted documents are recorded with the version in force, not with whatever the client claims.
	if got := agreementKeys(t, s); got != "seller-terms=1.0 payments-terms=1.0 data-processing=1.0" {
		t.Fatalf("seller agreements = %q (%v)", got, s["agreements"])
	}
}

func agreementKeys(t *testing.T, step map[string]any) string {
	t.Helper()
	var parts []string
	list, _ := step["agreements"].([]any)
	for _, a := range list {
		m := a.(map[string]any)
		parts = append(parts, fmt.Sprintf("%v=%v", m["key"], m["version"]))
	}
	return strings.Join(parts, " ")
}

// Providers accept the provider terms in place of the seller terms, "both" accepts both, nothing is recorded until
// the box is ticked, and a document that changed since the applicant read it must be read again.
func TestOnboardingAgreementsFollowTheOffering(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_x")
	save := func(extra map[string]any) resp {
		in := map[string]any{"categories": []string{"groceries"}, "w9Certified": true, "w9SignerName": "Kofi Mensah"}
		for k, v := range extra {
			in[k] = v
		}
		return b.do("PUT", "/v1/seller/onboarding/catalog-compliance", in)
	}
	if r := save(map[string]any{"agreementsAccepted": false}); r.status != 200 || up.StepBodies["catalog-compliance"]["agreements"] != nil {
		t.Fatalf("unaccepted = %d %v", r.status, up.StepBodies["catalog-compliance"])
	}
	up.Application["business"] = map[string]any{"offering": "SERVICES"}
	mustStatus(t, save(map[string]any{"agreementsAccepted": true}), 200)
	if got := agreementKeys(t, up.StepBodies["catalog-compliance"]); got != "provider-terms=1.0 payments-terms=1.0 data-processing=1.0" {
		t.Fatalf("provider agreements = %q", got)
	}
	up.Application["business"] = map[string]any{"offering": "BOTH"}
	mustStatus(t, save(map[string]any{"agreementsAccepted": true}), 200)
	if got := agreementKeys(t, up.StepBodies["catalog-compliance"]); got != "seller-terms=1.0 provider-terms=1.0 payments-terms=1.0 data-processing=1.0" {
		t.Fatalf("both agreements = %q", got)
	}
	// The versions the applicant saw match: accepted. One is stale: refused with a message on `agreements`.
	shown := []map[string]any{{"key": "provider-terms", "version": "1.0"}, {"key": "seller-terms", "version": "1.0"}}
	mustStatus(t, save(map[string]any{"agreementsAccepted": true, "agreements": shown}), 200)
	stale := []map[string]any{{"key": "seller-terms", "version": "0.9"}}
	r := save(map[string]any{"agreementsAccepted": true, "agreements": stale})
	mustStatus(t, r, 422)
	if r.json(t)["code"] != "LEGAL_DOCUMENT_CHANGED" || r.json(t)["errors"].(map[string]any)["agreements"] == nil {
		t.Fatalf("stale agreements = %s", r.body)
	}
}

// TestOnboardingStripeSessionAndRefresh: Connect.js gets the account session and the BFF's publishable key; the local
// stand-in is flagged; without a key a real session can't load.
func TestOnboardingStripeSessionAndRefresh(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_connect")
	se := b.do("POST", "/v1/seller/onboarding/stripe/session", nil)
	if j := se.json(t); se.status != 200 || j["clientSecret"] != "accs_secret_1" || j["publishableKey"] != "pk_test_connect" || j["localTest"] != false {
		t.Fatalf("session = %d %s", se.status, se.body)
	}
	if st := b.do("GET", "/v1/seller/onboarding", nil).json(t)["stripe"].(map[string]any); st["status"] != "IN_PROGRESS" {
		t.Fatalf("stripe = %v", st)
	}
	r := b.do("POST", "/v1/seller/onboarding/stripe/refresh", nil).json(t)
	if st := r["stripe"].(map[string]any); st["status"] != "VERIFIED" || st["payoutsEnabled"] != true {
		t.Fatalf("refreshed stripe = %v", st)
	}

	up.StripeLocalTest = true
	if j := b.do("POST", "/v1/seller/onboarding/stripe/session", nil).json(t); j["localTest"] != true {
		t.Fatalf("local session = %v", j)
	}

	_, b2 := startedApplicant(t, "")
	if r := b2.do("POST", "/v1/seller/onboarding/stripe/session", nil); r.status != 503 || r.json(t)["code"] != "STRIPE_NOT_CONFIGURED" {
		t.Fatalf("no key = %d %s", r.status, r.body)
	}
}

// TestOnboardingSubmitPlansAndDocuments: submit sends the application to review (or lists what is missing), plans are
// the application's kind, documents are private kyc-document uploads.
func TestOnboardingSubmitPlansAndDocuments(t *testing.T) {
	up, b := startedApplicant(t, "pk_test_x")
	if r := b.do("POST", "/v1/seller/onboarding/submit", nil); r.status != 422 || r.json(t)["errors"].(map[string]any)["compliance.agreementsAccepted"] == nil {
		t.Fatalf("incomplete submit = %d %s", r.status, r.body)
	}
	up.Application["compliance"] = map[string]any{"categories": []string{"groceries"}}
	r := b.do("POST", "/v1/seller/onboarding/submit", nil).json(t)
	all := steps(t, r)
	if r["status"] != "PENDING_REVIEW" || r["canResume"] != false || all[len(all)-1] != "review:Review & submit=WAITING" || r["submittedAt"] != "2026-10-10T12:00:00Z" ||
		r["timeline"].([]any)[0].(map[string]any)["action"] != "SUBMITTED" {
		t.Fatalf("submitted = %v", r)
	}

	pl := b.do("GET", "/v1/seller/onboarding/plans", nil).json(t)
	if pl["publishableKey"] != "pk_test_x" {
		t.Fatalf("plans key = %v", pl["publishableKey"])
	}
	plans := pl["plans"].([]any)
	growth := plans[1].(map[string]any)
	if len(plans) != 2 || growth["code"] != "seller_growth" || len(growth["prices"].([]any)) != 3 || growth["trialDays"] != 30.0 {
		t.Fatalf("plans = %v", plans)
	}

	if bad := b.do("POST", "/v1/seller/onboarding/uploads", map[string]any{"fileName": "permit.exe", "contentType": "application/x-msdownload", "size": 10}); bad.status != 422 {
		t.Fatalf("bad upload = %d %s", bad.status, bad.body)
	}
	up2 := b.do("POST", "/v1/seller/onboarding/uploads", map[string]any{"fileName": "permit.pdf", "contentType": "application/pdf", "size": 120000})
	if up2.status != 201 || up2.json(t)["assetId"] != "asset-1" || up.LastUploadPurpose != "kyc-document" || up2.json(t)["publicUrl"] != nil {
		t.Fatalf("upload = %d %s (purpose %q)", up2.status, up2.body, up.LastUploadPurpose)
	}
	mustStatus(t, b.do("POST", "/v1/seller/onboarding/uploads/asset-1/complete", nil), 200)
}
