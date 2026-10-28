package auth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type keyServer struct {
	mu      sync.Mutex
	key     *rsa.PrivateKey
	fetches int
}

func (s *keyServer) ServeHTTP(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.fetches++
	pub := s.key.PublicKey
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{"kid": "identity-1", "kty": "RSA",
		"n": base64.RawURLEncoding.EncodeToString(pub.N.Bytes()),
		"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pub.E)).Bytes())}}})
}

func (s *keyServer) swap(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	s.key = k
	s.mu.Unlock()
	return k
}

func sign(t *testing.T, key *rsa.PrivateKey, issuer string) string {
	t.Helper()
	tok := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{"iss": issuer, "sub": "user-1", "pt": "CUSTOMER",
		"exp": time.Now().Add(time.Minute).Unix()})
	tok.Header["kid"] = "identity-1"
	raw, err := tok.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// A restarted local identity signs with a new ephemeral key under the same key id: tokens it issues afterwards verify
// (one JWKS refetch), and a token with a bad signature cannot make the verifier refetch more than once per window.
func TestVerifierRefetchesWhenTheKeyChangesUnderTheSameKeyID(t *testing.T) {
	ks := &keyServer{}
	first := ks.swap(t)
	srv := httptest.NewServer(ks)
	defer srv.Close()
	v := NewJWKSVerifier(srv.URL, "", srv.Client())
	ctx := context.Background()

	if _, err := v.Verify(ctx, sign(t, first, srv.URL)); err != nil {
		t.Fatalf("first key: %v", err)
	}
	second := ks.swap(t)
	a, err := v.Verify(ctx, sign(t, second, srv.URL))
	if err != nil || a.Subject != "user-1" {
		t.Fatalf("after the key changed: %v %+v", err, a)
	}
	if ks.fetches != 2 {
		t.Fatalf("JWKS fetches = %d, want 2", ks.fetches)
	}
	forged, _ := rsa.GenerateKey(rand.Reader, 2048)
	for range 3 {
		if _, err := v.Verify(ctx, sign(t, forged, srv.URL)); err == nil {
			t.Fatal("a token signed with an unknown key must be rejected")
		}
	}
	if ks.fetches != 2 {
		t.Fatalf("bad signatures within the window must not refetch, fetches = %d", ks.fetches)
	}
}
