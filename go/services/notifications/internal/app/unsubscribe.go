package app

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// UnsubscribeClaims identify who unsubscribes from what.
type UnsubscribeClaims struct {
	RecipientKey string          `json:"k"`
	Address      string          `json:"a"`
	Channel      domain.Channel  `json:"ch"`
	Category     domain.Category `json:"c"`
	IssuedAt     int64           `json:"iat"`
}

// Unsubscriber signs and verifies one-click unsubscribe links (RFC 8058) for marketing email.
type Unsubscriber interface {
	// Headers returns List-Unsubscribe and List-Unsubscribe-Post headers for a marketing email.
	Headers(recipientKey, address string, category domain.Category, now time.Time) map[string]string
	// URL returns the signed one-click unsubscribe URL.
	URL(c UnsubscribeClaims) string
	// Verify checks a token and returns its claims.
	Verify(token string) (UnsubscribeClaims, error)
}

type unsubscriberImpl struct {
	baseURL string
	mailto  string
	key     []byte
	maxAge  time.Duration
}

var _ Unsubscriber = (*unsubscriberImpl)(nil)

// ErrInvalidToken is returned for tampered, malformed or expired unsubscribe tokens.
var ErrInvalidToken = errors.New("invalid unsubscribe token")

// NewUnsubscriber returns an HMAC-SHA256 link signer. baseURL is the public URL of this service; mailto is an
// optional mailto: fallback address.
func NewUnsubscriber(baseURL, mailto string, key []byte) Unsubscriber {
	return &unsubscriberImpl{baseURL: strings.TrimSuffix(baseURL, "/"), mailto: mailto, key: key, maxAge: 365 * 24 * time.Hour}
}

func (u *unsubscriberImpl) Headers(recipientKey, address string, category domain.Category, now time.Time) map[string]string {
	link := u.URL(UnsubscribeClaims{RecipientKey: recipientKey, Address: strings.ToLower(address),
		Channel: domain.ChannelEmail, Category: category, IssuedAt: now.Unix()})
	v := "<" + link + ">"
	if u.mailto != "" {
		v += ", <mailto:" + u.mailto + "?subject=unsubscribe>"
	}
	return map[string]string{"List-Unsubscribe": v, "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"}
}

func (u *unsubscriberImpl) URL(c UnsubscribeClaims) string {
	raw, _ := json.Marshal(c)
	payload := base64.RawURLEncoding.EncodeToString(raw)
	return u.baseURL + "/v1/unsubscribe?t=" + url.QueryEscape(payload+"."+u.sign(payload))
}

func (u *unsubscriberImpl) sign(payload string) string {
	m := hmac.New(sha256.New, u.key)
	m.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func (u *unsubscriberImpl) Verify(token string) (UnsubscribeClaims, error) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || !hmac.Equal([]byte(sig), []byte(u.sign(payload))) {
		return UnsubscribeClaims{}, ErrInvalidToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return UnsubscribeClaims{}, fmt.Errorf("%w: %w", ErrInvalidToken, err)
	}
	var c UnsubscribeClaims
	if err := json.Unmarshal(raw, &c); err != nil {
		return UnsubscribeClaims{}, fmt.Errorf("%w: %w", ErrInvalidToken, err)
	}
	if time.Since(time.Unix(c.IssuedAt, 0)) > u.maxAge || c.RecipientKey == "" {
		return UnsubscribeClaims{}, ErrInvalidToken
	}
	return c, nil
}
