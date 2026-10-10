// Package webhooksig verifies provider webhook signatures: Twilio (X-Twilio-Signature, HMAC-SHA1), Resend
// (Svix, HMAC-SHA256) and SendGrid Event Webhook (ECDSA P-256 over timestamp+payload).
package webhooksig

import (
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/sha1" //nolint:gosec // Twilio's signature scheme is HMAC-SHA1
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ErrInvalidSignature is returned when a webhook is not authentic.
var ErrInvalidSignature = errors.New("invalid webhook signature")

// Verifier authenticates a webhook request whose body has already been read.
type Verifier interface {
	Verify(r *http.Request, body []byte) error
}

// ---- Twilio ----

type twilioImpl struct {
	authToken string
	baseURL   string
}

var _ Verifier = (*twilioImpl)(nil)

// NewTwilio verifies X-Twilio-Signature. publicBaseURL is the externally visible scheme+host (the URL Twilio
// called), since TLS terminates at the gateway.
func NewTwilio(authToken, publicBaseURL string) Verifier {
	return &twilioImpl{authToken: authToken, baseURL: strings.TrimSuffix(publicBaseURL, "/")}
}

// TwilioSignature computes base64(HMAC-SHA1(authToken, url + sorted(key+value)...)).
func TwilioSignature(authToken, fullURL string, params url.Values) string {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	b.WriteString(fullURL)
	for _, k := range keys {
		vals := append([]string(nil), params[k]...)
		sort.Strings(vals)
		for _, v := range vals {
			b.WriteString(k)
			b.WriteString(v)
		}
	}
	m := hmac.New(sha1.New, []byte(authToken))
	m.Write([]byte(b.String()))
	return base64.StdEncoding.EncodeToString(m.Sum(nil))
}

func (t *twilioImpl) Verify(r *http.Request, body []byte) error {
	sig := r.Header.Get("X-Twilio-Signature")
	if sig == "" || t.authToken == "" {
		return ErrInvalidSignature
	}
	params, err := url.ParseQuery(string(body))
	if err != nil {
		return fmt.Errorf("%w: %w", ErrInvalidSignature, err)
	}
	full := t.baseURL + r.URL.RequestURI()
	if hmac.Equal([]byte(sig), []byte(TwilioSignature(t.authToken, full, params))) {
		return nil
	}
	return ErrInvalidSignature
}

// ---- Svix (Resend) ----

type svixImpl struct {
	key       []byte
	tolerance time.Duration
	now       func() time.Time
}

var _ Verifier = (*svixImpl)(nil)

// NewSvix verifies Svix-signed webhooks (Resend). secret is the "whsec_…" signing secret.
func NewSvix(secret string) (Verifier, error) {
	return NewSvixWithClock(secret, time.Now)
}

// NewSvixWithClock is NewSvix with an injectable clock (tests).
func NewSvixWithClock(secret string, now func() time.Time) (Verifier, error) {
	key, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(secret, "whsec_"))
	if err != nil || len(key) == 0 {
		return nil, errors.New("svix: secret must be whsec_<base64>")
	}
	return &svixImpl{key: key, tolerance: 5 * time.Minute, now: now}, nil
}

// SvixSignature computes the v1 signature for id, timestamp and body.
func SvixSignature(key []byte, id, ts string, body []byte) string {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(id + "." + ts + "."))
	m.Write(body)
	return "v1," + base64.StdEncoding.EncodeToString(m.Sum(nil))
}

func (s *svixImpl) Verify(r *http.Request, body []byte) error {
	id, ts, sigs := r.Header.Get("svix-id"), r.Header.Get("svix-timestamp"), r.Header.Get("svix-signature")
	if id == "" || ts == "" || sigs == "" {
		return ErrInvalidSignature
	}
	sec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return fmt.Errorf("%w: timestamp: %w", ErrInvalidSignature, err)
	}
	if d := s.now().Sub(time.Unix(sec, 0)); d > s.tolerance || d < -s.tolerance {
		return fmt.Errorf("%w: timestamp outside tolerance", ErrInvalidSignature)
	}
	want := SvixSignature(s.key, id, ts, body)
	for _, sig := range strings.Fields(sigs) { // several space-separated "v1,<sig>" during secret rotation
		if hmac.Equal([]byte(sig), []byte(want)) {
			return nil
		}
	}
	return ErrInvalidSignature
}

// ---- SendGrid ----

// SendGrid signature headers.
const (
	SendGridSignatureHeader = "X-Twilio-Email-Event-Webhook-Signature"
	SendGridTimestampHeader = "X-Twilio-Email-Event-Webhook-Timestamp"
)

type sendGridImpl struct{ key *ecdsa.PublicKey }

var _ Verifier = (*sendGridImpl)(nil)

// NewSendGrid verifies SendGrid's signed Event Webhook. publicKey is the base64 DER (PKIX) key from the console.
func NewSendGrid(publicKey string) (Verifier, error) {
	der, err := base64.StdEncoding.DecodeString(publicKey)
	if err != nil {
		return nil, fmt.Errorf("sendgrid: decode key: %w", err)
	}
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("sendgrid: parse key: %w", err)
	}
	ec, ok := k.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("sendgrid: key is not ECDSA")
	}
	return &sendGridImpl{key: ec}, nil
}

func (s *sendGridImpl) Verify(r *http.Request, body []byte) error {
	sig, err := base64.StdEncoding.DecodeString(r.Header.Get(SendGridSignatureHeader))
	if err != nil || len(sig) == 0 {
		return ErrInvalidSignature
	}
	h := sha256.New()
	h.Write([]byte(r.Header.Get(SendGridTimestampHeader)))
	h.Write(body)
	if !ecdsa.VerifyASN1(s.key, h.Sum(nil), sig) {
		return ErrInvalidSignature
	}
	return nil
}

// ---- Insecure (local only) ----

type allowAllImpl struct{}

var _ Verifier = (*allowAllImpl)(nil)

// NewAllowAll accepts every request. Only wired when webhooks.verify is false (local profile).
func NewAllowAll() Verifier { return allowAllImpl{} }

func (allowAllImpl) Verify(*http.Request, []byte) error { return nil }
