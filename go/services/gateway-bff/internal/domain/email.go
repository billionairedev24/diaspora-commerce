package domain

import (
	"errors"
	"strings"
)

// ErrInvalidEmail is returned by ParseEmail for anything that is not a plain address.
var ErrInvalidEmail = errors.New("invalid email address")

const localSpecial = "!#$%&'*+/=?^_`{|}~-"

// ParseEmail validates an address typed by an anonymous visitor and returns it trimmed and lower-cased. Only a bare
// dot-atom address is accepted: no display name, quotes, comments, IP literal, whitespace, control characters or
// non-ASCII (internationalised domains in punycode). The same rules run again in the notifications service.
func ParseEmail(raw string) (string, error) {
	e := strings.ToLower(strings.TrimSpace(raw))
	if e == "" || len(e) > 254 || strings.Count(e, "@") != 1 {
		return "", ErrInvalidEmail
	}
	local, host, _ := strings.Cut(e, "@")
	if !validEmailLocal(local) || !validEmailHost(host) {
		return "", ErrInvalidEmail
	}
	return e, nil
}

func validEmailLocal(s string) bool {
	if s == "" || len(s) > 64 || s[0] == '.' || s[len(s)-1] == '.' || strings.Contains(s, "..") {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '.' && strings.IndexByte(localSpecial, c) < 0 {
			return false
		}
	}
	return true
}

func validEmailHost(s string) bool {
	if s == "" || len(s) > 253 {
		return false
	}
	labels := strings.Split(s, ".")
	if len(labels) < 2 {
		return false
	}
	for _, l := range labels {
		if l == "" || len(l) > 63 || l[0] == '-' || l[len(l)-1] == '-' {
			return false
		}
		for i := 0; i < len(l); i++ {
			if c := l[i]; (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
				return false
			}
		}
	}
	tld := labels[len(labels)-1]
	if strings.HasPrefix(tld, "xn--") {
		return true
	}
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
