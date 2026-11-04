package domain_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestParseEmailCanonicalises(t *testing.T) {
	for in, want := range map[string]string{
		"ada@example.com":              "ada@example.com",
		"  Ada.Obi@EXAMPLE.Com\t":      "ada.obi@example.com",
		"first+tag@sub.example.co.uk":  "first+tag@sub.example.co.uk",
		"o'brien@example.ie":           "o'brien@example.ie",
		"a_b-c@my-shop.example.africa": "a_b-c@my-shop.example.africa",
		"x@xn--bcher-kva.example":      "x@xn--bcher-kva.example",
		"a@example.com\r\n":            "a@example.com", // only surrounding whitespace is trimmed
	} {
		got, err := domain.ParseEmail(in)
		if err != nil || got != want {
			t.Errorf("%q → %q, %v (want %q)", in, got, err, want)
		}
	}
}

func TestParseEmailRejectsEverythingOddly(t *testing.T) {
	long := strings.Repeat("a", 64)
	for _, in := range []string{"", " ", "plain", "@example.com", "a@", "a@b", "a@@example.com", "a@b@example.com",
		"a b@example.com", "a@exa mple.com", "<a@example.com>", "Ada <a@example.com>", "a@example.com (comment)",
		"a@example.com\r\nBcc: v@example.com", "a@example.com\nBcc: v@example.com", "a@example.com,b@example.com",
		"a@example.com;b@example.com", "\"a b\"@example.com", "a\\@b@example.com", "a@[127.0.0.1]", "a@[IPv6:::1]", "a@1.2.3.4",
		"a@localhost", ".a@example.com", "a.@example.com", "a..b@example.com", "a@.example.com", "a@example..com", "a@-example.com",
		"a@example-.com", "a@example.c", "a@example.c0m", "é@example.com", "a@exämple.com", "a@例え.jp", "a\x00@example.com",
		"a@example.com\x00", long + "a@example.com", "a@" + long + ".com", strings.Repeat("a.", 130) + "a@example.com",
		"a@" + strings.Repeat("b.", 126) + "com", "a@example.com" + strings.Repeat(" ", 3) + "x"} {
		if got, err := domain.ParseEmail(in); err == nil {
			t.Errorf("%q accepted as %q", in, got)
		}
	}
	if _, err := domain.ParseEmail(long + "@example.com"); err != nil {
		t.Errorf("a 64-character local part is allowed: %v", err)
	}
}

func TestMaskEmail(t *testing.T) {
	for in, want := range map[string]string{"ada@example.com": "a***@example.com", "x@y.io": "x***@y.io", "": "***", "nope": "***", "@x.io": "***"} {
		if got := domain.MaskEmail(in); got != want {
			t.Errorf("%q → %q (want %q)", in, got, want)
		}
	}
}

func TestNormalizeLocale(t *testing.T) {
	for in, want := range map[string]string{"": "en", "fr": "fr", "FR": "fr", "pt-BR": "pt-BR", "ar": "ar", "yo": "yo", "en\r\nX: 1": "en",
		"toolonglocale-tag-xyz": "en", "123": "en", "fr_CA": "en", " es ": "es"} {
		if got := domain.NormalizeLocale(in); got != want {
			t.Errorf("%q → %q (want %q)", in, got, want)
		}
	}
}
