// Package adtoken implements port.AdTokens with HMAC-SHA256 signed, URL-safe tokens: base64url(json).base64url(mac).
// Only search holds the key (ADS_TOKEN_KEY), so a click can only bill the campaign, product, price and search an ad was
// actually served with.
package adtoken

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// ErrInvalid is returned for malformed or forged tokens.
var ErrInvalid = errors.New("invalid ad token")

// MinKeyLength is the minimum secret length.
const MinKeyLength = 32

type hmacTokens struct {
	key []byte
}

var _ port.AdTokens = (*hmacTokens)(nil)

// New returns the HMAC token signer; the key must be at least 32 characters.
func New(key string) (port.AdTokens, error) {
	if len(key) < MinKeyLength {
		return nil, fmt.Errorf("ads token key must be at least %d characters", MinKeyLength)
	}
	return &hmacTokens{key: []byte(key)}, nil
}

var enc = base64.RawURLEncoding

func (h *hmacTokens) Sign(t domain.AdToken) (string, error) {
	body, err := json.Marshal(t)
	if err != nil {
		return "", fmt.Errorf("encode ad token: %w", err)
	}
	payload := enc.EncodeToString(body)
	return payload + "." + enc.EncodeToString(h.mac(payload)), nil
}

func (h *hmacTokens) Verify(token string) (domain.AdToken, error) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || payload == "" || sig == "" {
		return domain.AdToken{}, ErrInvalid
	}
	got, err := enc.DecodeString(sig)
	if err != nil || !hmac.Equal(got, h.mac(payload)) {
		return domain.AdToken{}, ErrInvalid
	}
	body, err := enc.DecodeString(payload)
	if err != nil {
		return domain.AdToken{}, ErrInvalid
	}
	var t domain.AdToken
	if err := json.Unmarshal(body, &t); err != nil || t.Version != 1 {
		return domain.AdToken{}, ErrInvalid
	}
	return t, nil
}

func (h *hmacTokens) mac(payload string) []byte {
	m := hmac.New(sha256.New, h.key)
	m.Write([]byte(payload))
	return m.Sum(nil)
}
