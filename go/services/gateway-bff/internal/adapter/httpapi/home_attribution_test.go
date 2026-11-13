package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// attrSite records what the handler asked the site service to count.
type attrSite struct {
	app.SiteService
	got []domain.HomeOrderAttribution
}

func (s *attrSite) RecordHomeOrder(_ context.Context, _ string, in domain.HomeOrderAttribution) {
	s.got = append(s.got, in)
}

func consentCookie(analytics bool) *http.Cookie {
	raw := `{"necessary":true,"functional":true,"analytics":` + map[bool]string{true: "true", false: "false"}[analytics] + `,"advertising":false,"dns":false}`
	return &http.Cookie{Name: domain.ConsentCookie, Value: url.QueryEscape(raw)}
}

func attribute(site *attrSite, cookies ...*http.Cookie) {
	h := &handlersImpl{s: Services{Site: site}}
	r := httptest.NewRequest(http.MethodPost, "/v1/checkout/orders", nil)
	r.Header.Set("Accept-Language", "fr-FR,fr;q=0.9")
	for _, c := range cookies {
		r.AddCookie(c)
	}
	h.attributeHomeOrder(r, domain.OrderConfirmation{OrderNumber: "AT-1", TotalMinor: 4599, Currency: "USD"})
}

func TestOrderAttributionReadsTheHomeCookieOnlyWithAnalyticsConsent(t *testing.T) {
	hm := &http.Cookie{Name: domain.HomeAttributionCookie, Value: url.QueryEscape("deals:3")}
	variant := &http.Cookie{Name: domain.HomeVariantCookie, Value: "b"}

	for name, cookies := range map[string][]*http.Cookie{
		"no consent cookie at all":    {hm},
		"analytics refused":           {consentCookie(false), hm},
		"unreadable consent":          {{Name: domain.ConsentCookie, Value: "%%%"}, hm},
		"consent but no module click": {consentCookie(true)},
		"unknown module":              {consentCookie(true), {Name: domain.HomeAttributionCookie, Value: "AT0000123%3A1"}},
	} {
		site := &attrSite{}
		attribute(site, cookies...)
		if len(site.got) != 0 {
			t.Errorf("%s: nothing may be counted, got %+v", name, site.got)
		}
	}

	site := &attrSite{}
	attribute(site, consentCookie(true), hm, variant)
	if len(site.got) != 1 {
		t.Fatalf("consent and a module click count once: %+v", site.got)
	}
	want := domain.HomeOrderAttribution{Module: "deals", Variant: "b", Locale: "fr-FR,fr;q=0.9", TotalMinor: 4599}
	if site.got[0] != want {
		t.Errorf("got %+v, want %+v", site.got[0], want)
	}
}

func TestAnalyticsConsentParsing(t *testing.T) {
	for raw, want := range map[string]bool{
		"":                                     false,
		url.QueryEscape(`{"analytics":true}`):  true,
		url.QueryEscape(`{"analytics":false}`): false,
		url.QueryEscape(`{"all":true}`):        true,
		url.QueryEscape(`{"functional":true}`): false,
		"not json":                             false,
	} {
		if got := domain.AnalyticsConsented(raw); got != want {
			t.Errorf("AnalyticsConsented(%q) = %v, want %v", raw, got, want)
		}
	}
}
