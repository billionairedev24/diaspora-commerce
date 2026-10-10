package domain_test

import (
	"fmt"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestTemplatePlaceholders(t *testing.T) {
	cases := map[string]string{
		"Hi {{.firstName}}, order {{ .orderNumber }} ({{.total}})":                           "[firstName orderNumber total]",
		"{{if .note}}Note: {{.note}}{{end}}{{- .trackingLine -}}":                            "[note trackingLine]",
		"{{range .lines}}{{.title}} x{{.qty}} {{$.currency}}{{end}}{{with .x}}{{.y}}{{end}}": "[currency lines x]",
		`{{printf "%.2f" .amount}} {{/* .commented */}} {{template "t" .}}`:                  "[amount]",
		"no placeholders": "[]",
	}
	for src, want := range cases {
		if got := fmt.Sprint(domain.TemplatePlaceholders(src)); got != want {
			t.Errorf("%q: %s, want %s", src, got, want)
		}
	}
}

func TestCountSmsSegments(t *testing.T) {
	cases := []struct {
		body                        string
		encoding                    string
		chars, units, segments, per int
	}{
		{"", "GSM-7", 0, 0, 0, 160},
		{"AfroTransact: code 123456", "GSM-7", 25, 25, 1, 160},
		{"Total €5", "GSM-7", 8, 9, 1, 160},
		{strings.Repeat("a", 161), "GSM-7", 161, 161, 2, 153},
		{"Sat, Oct 17 · 10:00 AM", "UCS-2", 22, 22, 1, 70},
		{"Hi 👋" + strings.Repeat("a", 67), "UCS-2", 71, 72, 2, 67},
	}
	for _, c := range cases {
		got := domain.CountSmsSegments(c.body)
		if got.Encoding != c.encoding || got.Characters != c.chars || got.Units != c.units || got.Segments != c.segments || got.PerSegment != c.per {
			t.Errorf("%q: %+v", c.body, got)
		}
	}
}

func TestTemplateKeyAndLocale(t *testing.T) {
	if !domain.ValidTemplateKey("seller.payouts.paused.suspended") || domain.ValidTemplateKey("../etc") || domain.ValidTemplateKey("Order") {
		t.Fatal("keys")
	}
	if !domain.ValidTemplateLocale("fr-CA") || !domain.ValidTemplateLocale("en") || domain.ValidTemplateLocale("english") {
		t.Fatal("locales")
	}
}
