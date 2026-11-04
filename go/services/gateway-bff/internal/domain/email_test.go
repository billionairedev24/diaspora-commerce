package domain_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestParseEmail(t *testing.T) {
	for in, want := range map[string]string{"Ada.Obi@EXAMPLE.com": "ada.obi@example.com", " a+b@x.example.co.uk ": "a+b@x.example.co.uk"} {
		if got, err := domain.ParseEmail(in); err != nil || got != want {
			t.Errorf("%q → %q %v", in, got, err)
		}
	}
	for _, in := range []string{"", "plain", "a@b", "a@@b.com", "a b@example.com", "Ada <a@example.com>", "a@example.com\r\nBcc: b@example.com",
		"a@example.com,b@example.com", "\"a\"@example.com", "a@[1.2.3.4]", "a@1.2.3.4", ".a@example.com", "a..b@example.com",
		"a@-x.com", "a@example.c", "é@example.com", "a\x00@example.com", strings.Repeat("a", 65) + "@example.com"} {
		if got, err := domain.ParseEmail(in); err == nil {
			t.Errorf("%q accepted as %q", in, got)
		}
	}
}
