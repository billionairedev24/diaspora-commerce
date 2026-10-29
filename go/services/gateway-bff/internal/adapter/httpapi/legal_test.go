package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// S191: legal pages come from content, are public and cacheable, and unknown documents or versions are 404.
func TestLegalDocuments(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	r := b.do("GET", "/v1/legal/sms-terms?locale=fr", nil)
	mustStatus(t, r, 200)
	if cc := r.header.Get("Cache-Control"); !strings.HasPrefix(cc, "public") {
		t.Fatalf("Cache-Control = %q", cc)
	}
	p := r.json(t)
	if p["key"] != "sms-terms" || p["translationFallback"] != true || p["draftBanner"] != true ||
		len(p["outline"].([]any)) != 1 || len(p["versions"].([]any)) != 1 || !strings.Contains(p["body"].(string), "## Program") {
		t.Fatalf("page = %v", p)
	}
	mustStatus(t, b.do("GET", "/v1/legal/terms", nil), 404)
	mustStatus(t, b.do("GET", "/v1/legal/sms-terms?version=0.1", nil), 404)
	mustStatus(t, b.do("GET", "/v1/legal/future-policy", nil), 404) // content knows it, the BFF contract does not

	policy := b.do("GET", "/v1/consent/cookie-policy", nil).json(t)
	if policy["noticeVersion"] != "2.1" || len(policy["categories"].([]any)) != 2 || len(policy["cookies"].([]any)) != 1 {
		t.Fatalf("policy = %v", policy)
	}
}

// Every document of the legal center is served (the twelve added ones included), in every locale the storefront
// offers, with the translation-fallback flag set for the locales that have no reviewed text yet.
func TestEveryLegalDocumentIsServedInEveryLocale(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	if len(domain.LegalDocumentKeys) != 21 {
		t.Fatalf("the legal center has %d documents, want 21", len(domain.LegalDocumentKeys))
	}
	for _, key := range domain.LegalDocumentKeys {
		for _, loc := range []string{"en", "fr", "es", "ar", "yo", "de", ""} {
			r := b.do("GET", "/v1/legal/"+key+"?locale="+loc, nil)
			mustStatus(t, r, 200)
			p := r.json(t)
			if p["key"] != key || p["href"] != "/legal/"+key || len(p["outline"].([]any)) == 0 {
				t.Fatalf("%s/%s page = %v", key, loc, p)
			}
			// An unsupported locale is served as English (never an error), so the fallback notice shows.
			wantFallback := loc != "en"
			if loc == "" || loc == "de" {
				wantFallback = false
			}
			if got, _ := p["translationFallback"].(bool); got != wantFallback {
				t.Fatalf("%s/%s translationFallback = %v, want %v", key, loc, got, wantFallback)
			}
		}
	}
}

// The legal center index lists the documents this BFF knows, in the legal center's order, and is public.
func TestLegalIndex(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	r := b.do("GET", "/v1/legal", nil)
	mustStatus(t, r, 200)
	if cc := r.header.Get("Cache-Control"); !strings.HasPrefix(cc, "public") {
		t.Fatalf("Cache-Control = %q", cc)
	}
	docs := r.json(t)["documents"].([]any)
	if len(docs) != len(domain.LegalDocumentKeys) {
		t.Fatalf("%d documents, want %d (unknown keys must be dropped)", len(docs), len(domain.LegalDocumentKeys))
	}
	for i, d := range docs {
		row := d.(map[string]any)
		if row["key"] != domain.LegalDocumentKeys[i] || row["href"] != "/legal/"+domain.LegalDocumentKeys[i] || row["version"] == "" {
			t.Fatalf("row %d = %v", i, row)
		}
	}
}

// S192: the BFF chooses the consent subject (never the session id itself) and forwards GPC; GPC implies the
// sale/sharing opt-out.
func TestConsentRecordsSubjectAndGPC(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true})
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"}), 200) // creates a guest session
	mustStatus(t, b.do("POST", "/v1/consent/records", map[string]any{"categories": map[string]bool{"advertising": true}, "source": "telepathy"}), 422)

	b.headers["Sec-GPC"] = "1"
	r := b.do("POST", "/v1/consent/records", map[string]any{"categories": map[string]bool{"advertising": true}, "source": "banner"})
	mustStatus(t, r, 201)
	rec := r.json(t)
	if rec["saleOptOut"] != true || rec["gpc"] != true || rec["categories"].(map[string]any)["advertising"] != false {
		t.Fatalf("record = %v", rec)
	}
	sent := bff.Upstreams.LastConsent
	subject, _ := sent["subject"].(string)
	if !strings.HasPrefix(subject, "anon:") || strings.Contains(subject, b.cookie) || bff.Upstreams.LastConsentGPC != "1" || sent["saleOptOut"] != true {
		t.Fatalf("sent %v (Sec-GPC %q)", sent, bff.Upstreams.LastConsentGPC)
	}
	delete(b.headers, "Sec-GPC")
	mustStatus(t, b.do("POST", "/v1/consent/records", map[string]any{"categories": map[string]bool{}, "source": "preferences"}), 201)
	if bff.Upstreams.LastConsent["subject"] != subject {
		t.Fatal("a guest keeps the same consent subject for the session")
	}

	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("POST", "/v1/consent/records", map[string]any{"categories": map[string]bool{}, "saleOptOut": true, "source": "privacy-choices"}), 201)
	if s, _ := bff.Upstreams.LastConsent["subject"].(string); !strings.HasPrefix(s, "user:") {
		t.Fatalf("signed-in subject = %q", s)
	}
}

// Your Privacy Choices: validated with the storefront's wording; a signed-in customer's token is relayed.
func TestPrivacyRequests(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true})
	r := b.do("POST", "/v1/privacy/requests", map[string]any{"type": "sell", "email": "nope", "state": "ZZ"})
	mustStatus(t, r, 422)
	errs := r.json(t)["errors"].(map[string]any)
	if errs["type"] != "Choose what you would like to do" || errs["email"] != "Enter the email address on your account" || errs["state"] != "Choose your state" {
		t.Fatalf("errors = %v", errs)
	}

	r = b.do("POST", "/v1/privacy/requests", map[string]any{"type": "OptOut", "email": " ada@example.com ", "state": "tx", "details": "  ads  "})
	mustStatus(t, r, 201)
	if rc := r.json(t); rc["reference"] != "PR-100001" || rc["dueAt"] == nil {
		t.Fatalf("receipt = %v", rc)
	}
	sent := bff.Upstreams.LastPrivacyRequest
	if sent["type"] != "optout" || sent["email"] != "ada@example.com" || sent["state"] != "TX" || sent["details"] != "ads" || bff.Upstreams.LastPrivacyBearer != "" {
		t.Fatalf("sent %v (bearer %q)", sent, bff.Upstreams.LastPrivacyBearer)
	}

	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("POST", "/v1/privacy/requests", map[string]any{"type": "access", "email": "ada@example.com", "state": "NY"}), 201)
	if bff.Upstreams.LastPrivacyBearer == "" {
		t.Fatal("the signed-in customer's token must be relayed so content attaches the account")
	}
}
