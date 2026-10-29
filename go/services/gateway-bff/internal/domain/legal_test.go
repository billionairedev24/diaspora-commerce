package domain_test

import (
	"slices"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestLegalGroupsPlaceEveryDocumentExactlyOnce(t *testing.T) {
	var all []string
	for _, g := range domain.LegalGroups {
		all = append(all, g.Documents...)
	}
	if len(all) != len(domain.LegalDocumentKeys) {
		t.Fatalf("groups hold %d documents, the legal center has %d", len(all), len(domain.LegalDocumentKeys))
	}
	for _, k := range domain.LegalDocumentKeys {
		if n := countOf(all, k); n != 1 {
			t.Errorf("%s appears in %d groups", k, n)
		}
		if domain.LegalGroupTitle(k) == "" || !domain.IsLegalDocument(k) {
			t.Errorf("%s has no group title", k)
		}
	}
	if domain.LegalGroupTitle("nope") != "" || domain.IsLegalDocument("terms") {
		t.Error("unknown keys have no group and are not documents")
	}
	titles := make([]string, 0, len(domain.LegalGroups))
	for _, g := range domain.LegalGroups {
		titles = append(titles, g.Title)
	}
	want := []string{"Customers", "Payments and money", "Sellers and providers", "Privacy and cookies", "Policies"}
	if !slices.Equal(titles, want) {
		t.Fatalf("groups = %v, want %v", titles, want)
	}
	if !slices.Equal(domain.LegalLocales, []string{"fr", "es", "ar", "yo"}) {
		t.Fatalf("translation locales = %v", domain.LegalLocales)
	}
}

func countOf(list []string, k string) int {
	n := 0
	for _, x := range list {
		if x == k {
			n++
		}
	}
	return n
}

func TestRequiredAgreementsByOffering(t *testing.T) {
	cases := map[string][]string{
		"PRODUCTS": {"seller-terms", "payments-terms", "data-processing"},
		"":         {"seller-terms", "payments-terms", "data-processing"},
		"services": {"provider-terms", "payments-terms", "data-processing"},
		"BOTH":     {"seller-terms", "provider-terms", "payments-terms", "data-processing"},
	}
	for offering, want := range cases {
		if got := domain.RequiredAgreements(offering); !slices.Equal(got, want) {
			t.Errorf("RequiredAgreements(%q) = %v, want %v", offering, got, want)
		}
	}
}
