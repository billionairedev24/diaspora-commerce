package linktoken_test

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/linktoken"
)

const (
	keyA = "0123456789abcdef0123456789abcdef-a"
	keyB = "0123456789abcdef0123456789abcdef-b"
	id   = "6f1c9a52-8a0e-4d0b-9a7e-3c2b1d0e9f11"
)

func TestSignVerifyAndRotation(t *testing.T) {
	old, err := linktoken.New(keyA)
	if err != nil {
		t.Fatal(err)
	}
	exp := time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC)
	tok, err := old.Sign(id, exp)
	if err != nil {
		t.Fatal(err)
	}
	if len(tok) > 80 || strings.ContainsAny(tok, "+/=") {
		t.Fatalf("token must be short and URL-safe: %q", tok)
	}
	gotID, gotExp, err := old.Verify(tok)
	if err != nil || gotID != id || !gotExp.Equal(exp) {
		t.Fatalf("verify: %s %s %v", gotID, gotExp, err)
	}
	// After rotation the new key signs and the previous one still verifies old links.
	rotated, err := linktoken.New(keyB, keyA)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := rotated.Verify(tok); err != nil {
		t.Fatalf("old links must survive rotation: %v", err)
	}
	newTok, _ := rotated.Sign(id, exp)
	if _, _, err := old.Verify(newTok); !errors.Is(err, linktoken.ErrInvalid) {
		t.Fatal("a signer without the new key must refuse new tokens")
	}
}

func TestTamperedAndMalformedTokensAreRefused(t *testing.T) {
	s, _ := linktoken.New(keyA)
	tok, _ := s.Sign(id, time.Now().Add(time.Hour))
	b := []byte(tok)
	b[5] ^= 1
	for _, bad := range []string{string(b), tok[:len(tok)-2], "", "not-a-token", tok + "AA"} {
		if _, _, err := s.Verify(bad); !errors.Is(err, linktoken.ErrInvalid) {
			t.Fatalf("%q must be refused, got %v", bad, err)
		}
	}
	if _, err := linktoken.New("short"); err == nil {
		t.Fatal("short keys must be refused")
	}
	if _, err := s.Sign("not-a-uuid", time.Now()); err == nil {
		t.Fatal("asset ids are UUIDs")
	}
}
