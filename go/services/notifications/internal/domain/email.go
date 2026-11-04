package domain

import (
	"errors"
	"strings"
)

// ErrInvalidEmail is returned by ParseEmail for anything that is not a plain, deliverable-looking address.
var ErrInvalidEmail = errors.New("email is not a valid address")

const (
	maxEmailLen  = 254 // RFC 5321 path limit
	maxLocalLen  = 64
	maxDomainLen = 253
	maxLabelLen  = 63
	localSpecial = "!#$%&'*+/=?^_`{|}~-"
)

// ParseEmail validates an address typed by an unauthenticated visitor and returns its canonical form: trimmed and
// lower-cased. It is deliberately stricter than RFC 5322: only a bare dot-atom address is accepted (no display
// name, quoted local part, comment, IP literal, spaces, control characters or non-ASCII), so the result is safe to
// store, to use as a rate-limit key and to put in a To header. Internationalised domains must be given in
// punycode (xn--).
func ParseEmail(raw string) (string, error) {
	e := strings.ToLower(strings.TrimSpace(raw))
	if e == "" || len(e) > maxEmailLen || strings.Count(e, "@") != 1 {
		return "", ErrInvalidEmail
	}
	local, host, _ := strings.Cut(e, "@")
	if !validLocalPart(local) || !validHost(host) {
		return "", ErrInvalidEmail
	}
	return e, nil
}

func validLocalPart(s string) bool {
	if s == "" || len(s) > maxLocalLen || s[0] == '.' || s[len(s)-1] == '.' || strings.Contains(s, "..") {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= '0' && c <= '9', c == '.', strings.IndexByte(localSpecial, c) >= 0:
		default:
			return false
		}
	}
	return true
}

func validHost(s string) bool {
	if s == "" || len(s) > maxDomainLen {
		return false
	}
	labels := strings.Split(s, ".")
	if len(labels) < 2 {
		return false
	}
	for _, l := range labels {
		if l == "" || len(l) > maxLabelLen || l[0] == '-' || l[len(l)-1] == '-' {
			return false
		}
		for i := 0; i < len(l); i++ {
			c := l[i]
			if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
				return false
			}
		}
	}
	tld := labels[len(labels)-1]
	if strings.HasPrefix(tld, "xn--") {
		return true
	}
	// A TLD is letters only and at least two long: this also rejects dotted-quad hosts like 1.2.3.4.
	if len(tld) < 2 {
		return false
	}
	for i := 0; i < len(tld); i++ {
		if tld[i] < 'a' || tld[i] > 'z' {
			return false
		}
	}
	return true
}

// MaskEmail returns a log-safe form of an address ("a***@example.com"). Logs must never carry the full address.
func MaskEmail(e string) string {
	local, host, ok := strings.Cut(strings.TrimSpace(e), "@")
	if !ok || local == "" {
		return "***"
	}
	r := []rune(local)
	return string(r[:1]) + "***@" + host
}
