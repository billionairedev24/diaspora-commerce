package carrierhttp

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// TokenFetcher performs a carrier's OAuth client-credentials exchange and returns the token and its lifetime.
type TokenFetcher func(ctx context.Context) (token string, ttl time.Duration, err error)

// TokenSource caches a bearer token until shortly before it expires. Concurrent callers share one exchange.
type TokenSource interface {
	Authorizer
	Token(ctx context.Context) (string, error)
}

type tokenSourceImpl struct {
	fetch TokenFetcher
	skew  time.Duration
	now   func() time.Time

	mu      sync.Mutex
	token   string
	expires time.Time
}

var _ TokenSource = (*tokenSourceImpl)(nil)

// NewTokenSource returns a cached TokenSource; tokens are refreshed skew before expiry (60s by default).
func NewTokenSource(fetch TokenFetcher, now func() time.Time) TokenSource {
	if now == nil {
		now = time.Now
	}
	return &tokenSourceImpl{fetch: fetch, skew: 60 * time.Second, now: now}
}

func (t *tokenSourceImpl) Token(ctx context.Context) (string, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.token != "" && t.now().Before(t.expires) {
		return t.token, nil
	}
	tok, ttl, err := t.fetch(ctx)
	if err != nil {
		return "", err
	}
	if ttl <= t.skew {
		ttl = 2 * t.skew
	}
	t.token, t.expires = tok, t.now().Add(ttl-t.skew)
	return tok, nil
}

func (t *tokenSourceImpl) Authorize(ctx context.Context, req *http.Request) error {
	tok, err := t.Token(ctx)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	return nil
}

func (t *tokenSourceImpl) Invalidate() {
	t.mu.Lock()
	t.token = ""
	t.mu.Unlock()
}

type basicAuth struct{ user, pass string }

var _ Authorizer = basicAuth{}

// BasicAuth returns an Authorizer for HTTP Basic credentials (DHL MyDHL API).
func BasicAuth(user, pass string) Authorizer { return basicAuth{user: user, pass: pass} }

func (b basicAuth) Authorize(_ context.Context, req *http.Request) error {
	req.SetBasicAuth(b.user, b.pass)
	return nil
}

func (basicAuth) Invalidate() {}

// TokenError maps a failed token exchange to a domain error.
func TokenError(carrier domain.CarrierCode, status int, msg string) error {
	return &domain.CarrierError{Carrier: carrier, Status: status, Message: "token exchange: " + msg, Kind: tokenKind(status)}
}

func tokenKind(status int) error {
	if status == http.StatusBadRequest || status == http.StatusUnauthorized || status == http.StatusForbidden {
		return domain.ErrCarrierAuth
	}
	return KindFor(status)
}

// VerifyHMAC checks an HMAC-SHA256 signature of body (base64 or hex encoded, optionally "sha256=" prefixed)
// in constant time.
func VerifyHMAC(secret, signature string, body []byte) bool {
	if secret == "" || signature == "" {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	want := mac.Sum(nil)
	sig := strings.TrimPrefix(strings.TrimSpace(signature), "sha256=")
	if got, err := base64.StdEncoding.DecodeString(sig); err == nil && hmac.Equal(got, want) {
		return true
	}
	if got, err := hex.DecodeString(sig); err == nil && hmac.Equal(got, want) {
		return true
	}
	return false
}

// SignHMAC returns the base64 HMAC-SHA256 of body (used by tests and documentation).
func SignHMAC(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

// EqualSecret compares a shared credential in constant time.
func EqualSecret(want, got string) bool {
	return want != "" && subtle.ConstantTimeCompare([]byte(want), []byte(got)) == 1
}

// AuthFailure re-labels a failed token exchange: 400/401/403 from a token endpoint mean bad client credentials,
// which no retry will fix.
func AuthFailure(err error) error {
	var ce *domain.CarrierError
	if errors.As(err, &ce) {
		if ce.Status == http.StatusBadRequest || ce.Status == http.StatusUnauthorized || ce.Status == http.StatusForbidden {
			ce.Kind = domain.ErrCarrierAuth
		}
		ce.Message = "token exchange: " + ce.Message
		return ce
	}
	return fmt.Errorf("token exchange: %w", err)
}

// FlexInt decodes integers that carriers send either as JSON numbers or as strings ("14399").
type FlexInt int64

// UnmarshalJSON accepts 3599, "3599" and "".
func (f *FlexInt) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	n, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return fmt.Errorf("flex int %q: %w", s, err)
	}
	*f = FlexInt(n)
	return nil
}

// FlexFloat decodes decimals sent either as JSON numbers or strings ("12.34").
type FlexFloat float64

// UnmarshalJSON accepts 12.34, "12.34" and "".
func (f *FlexFloat) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	n, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return fmt.Errorf("flex float %q: %w", s, err)
	}
	*f = FlexFloat(n)
	return nil
}

// SplitName splits "Ada Obi" into first and last names for carriers that want them separately.
func SplitName(full string) (first, last string) {
	full = strings.TrimSpace(full)
	i := strings.LastIndexByte(full, ' ')
	if i < 0 {
		return full, full
	}
	return strings.TrimSpace(full[:i]), full[i+1:]
}

// Dec formats a float for carriers that want decimals as strings ("2.5").
func Dec(f float64) string { return strconv.FormatFloat(f, 'f', -1, 64) }
