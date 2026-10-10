package webhooksig_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/webhooksig"
)

func TestTwilioSignature(t *testing.T) {
	const token = "12345"
	params := url.Values{"From": {"+15551234567"}, "Body": {"STOP"}, "To": {"+18885550100"}, "MessageSid": {"SM1"}}
	full := "https://api.afrotransact.com/notifications/v1/webhooks/twilio/inbound?x=1"

	// Independent reference: URL followed by params sorted by key, then base64(HMAC-SHA1).
	m := hmac.New(sha1.New, []byte(token))
	m.Write([]byte(full + "BodySTOPFrom+15551234567MessageSidSM1To+18885550100"))
	want := base64.StdEncoding.EncodeToString(m.Sum(nil))
	if got := webhooksig.TwilioSignature(token, full, params); got != want {
		t.Fatalf("signature %s want %s", got, want)
	}

	v := webhooksig.NewTwilio(token, "https://api.afrotransact.com/notifications")
	body := []byte(params.Encode())
	req := httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/twilio/inbound?x=1", strings.NewReader(string(body)))
	req.Header.Set("X-Twilio-Signature", want)
	if err := v.Verify(req, body); err != nil {
		t.Fatal(err)
	}
	tampered := []byte(strings.Replace(string(body), "STOP", "START", 1))
	if err := v.Verify(req, tampered); !errors.Is(err, webhooksig.ErrInvalidSignature) {
		t.Fatal("tampered body accepted")
	}
	if err := webhooksig.NewTwilio("other", "https://api.afrotransact.com/notifications").Verify(req, body); err == nil {
		t.Fatal("wrong auth token accepted")
	}
	req.Header.Del("X-Twilio-Signature")
	if err := v.Verify(req, body); err == nil {
		t.Fatal("missing signature accepted")
	}
}

func TestSvix(t *testing.T) {
	key := []byte("resend-signing-key-0123456789abc")
	secret := "whsec_" + base64.StdEncoding.EncodeToString(key)
	now := time.Unix(1_760_000_000, 0)
	v, err := webhooksig.NewSvixWithClock(secret, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(`{"type":"email.delivered","data":{"email_id":"e1"}}`)
	ts := strconv.FormatInt(now.Unix(), 10)
	m := hmac.New(sha256.New, key)
	m.Write([]byte("msg_1." + ts + "." + string(body)))
	sig := "v1," + base64.StdEncoding.EncodeToString(m.Sum(nil))
	if sig != webhooksig.SvixSignature(key, "msg_1", ts, body) {
		t.Fatal("reference mismatch")
	}
	req := httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/resend", nil)
	req.Header.Set("svix-id", "msg_1")
	req.Header.Set("svix-timestamp", ts)
	req.Header.Set("svix-signature", "v1,b2xk "+sig) // rotated secrets send several signatures
	if err := v.Verify(req, body); err != nil {
		t.Fatal(err)
	}
	if err := v.Verify(req, append(body, ' ')); err == nil {
		t.Fatal("modified body accepted")
	}
	req.Header.Set("svix-timestamp", strconv.FormatInt(now.Add(-10*time.Minute).Unix(), 10))
	if err := v.Verify(req, body); err == nil {
		t.Fatal("replayed (stale) webhook accepted")
	}
	if _, err := webhooksig.NewSvix("not-base64!"); err == nil {
		t.Fatal("bad secret accepted")
	}
}

func TestSendGridECDSA(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	v, err := webhooksig.NewSendGrid(base64.StdEncoding.EncodeToString(der))
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(`[{"email":"a@b.c","event":"delivered"}]`)
	ts := "1760000000"
	h := sha256.Sum256(append([]byte(ts), body...))
	sig, _ := ecdsa.SignASN1(rand.Reader, k, h[:])
	req := httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/sendgrid", nil)
	req.Header.Set(webhooksig.SendGridSignatureHeader, base64.StdEncoding.EncodeToString(sig))
	req.Header.Set(webhooksig.SendGridTimestampHeader, ts)
	if err := v.Verify(req, body); err != nil {
		t.Fatal(err)
	}
	req.Header.Set(webhooksig.SendGridTimestampHeader, "1760000001")
	if err := v.Verify(req, body); err == nil {
		t.Fatal("signature over another timestamp accepted")
	}
}
