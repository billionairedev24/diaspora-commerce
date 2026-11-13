package domain

import (
	"encoding/json"
	"net/url"
	"strings"
)

// Cookies the order attribution reads (docs/engineering/dynamic-home.md, "Order attribution"). They are first-party
// cookies the storefront keeps; the gateway reads them only when the shopper's own consent cookie says Analytics is on.
const (
	// HomeAttributionCookie holds `module:position` of the last home module click (24 hours, Analytics consent only).
	HomeAttributionCookie = "at_hm"
	// ConsentCookie is the shopper's cookie choice (URL-encoded JSON).
	ConsentCookie = "at_consent"
	// HomeVariantCookie is the experiment variant the Worker assigned.
	HomeVariantCookie = "at_variant"
)

// AnalyticsConsented reports whether the consent cookie value says the shopper allowed Analytics. An absent, unreadable or
// refusing cookie is "no": for attribution, no consent means no read (unlike the functional visit cookies, where no
// choice counts as on).
func AnalyticsConsented(raw string) bool {
	if raw == "" {
		return false
	}
	text, err := url.QueryUnescape(strings.ReplaceAll(raw, "+", "%2B"))
	if err != nil {
		return false
	}
	var c map[string]any
	if json.Unmarshal([]byte(text), &c) != nil {
		return false
	}
	yes := func(k string) bool { v, _ := c[k].(bool); return v }
	return yes("analytics") || yes("all") || yes("accepted")
}

// ParseHomeAttribution returns the module of an `at_hm` cookie value (`module:position`). Only a known module key is
// accepted; anything else is "no attribution". The position is deliberately dropped: it is never a metric label.
func ParseHomeAttribution(raw string) (module string, ok bool) {
	text, err := url.QueryUnescape(raw)
	if err != nil {
		return "", false
	}
	m, _, _ := strings.Cut(text, ":")
	if !IsModule(m) {
		return "", false
	}
	return m, true
}

// HomeOrderAttribution is one placed order credited to the home module whose link the shopper last clicked. It carries
// no shopper, order or product identity: only the module, the variant, the locale and the total in minor units.
type HomeOrderAttribution struct {
	Module     string
	Variant    string
	Locale     string
	TotalMinor int64
}
