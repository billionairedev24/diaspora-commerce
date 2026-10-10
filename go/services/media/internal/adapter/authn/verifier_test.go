package authn

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
)

// stubVerifier accepts the tokens it knows (as if signature, issuer and its audience checked out).
type stubVerifier map[string]auth.Actor

func (s stubVerifier) Verify(_ context.Context, token string) (auth.Actor, error) {
	if a, ok := s[token]; ok {
		return a, nil
	}
	return auth.Actor{}, auth.ErrUnauthenticated
}

func TestAcceptsMediaTokensAndTheNamedClientsOwnServiceTokens(t *testing.T) {
	staff := auth.Actor{Subject: "staff-1", Type: "STAFF"}
	retail := auth.Actor{Subject: "retail-service", Type: "SERVICE"}
	bff := auth.Actor{Subject: "gateway-bff", Type: "SERVICE"}
	bffUser := auth.Actor{Subject: "4f1c0f5e-user", Type: "CUSTOMER"}
	v := newVerifier(stubVerifier{"media-token": staff},
		[]string{"retail-service", "gateway-bff"},
		[]auth.Verifier{
			stubVerifier{"retail-token": retail, "retail-forged-subject": bff},
			stubVerifier{"bff-token": bff, "bff-user-token": bffUser},
		})
	ctx := context.Background()

	for token, want := range map[string]auth.Actor{"media-token": staff, "retail-token": retail, "bff-token": bff} {
		got, err := v.Verify(ctx, token)
		if err != nil || got.Subject != want.Subject {
			t.Fatalf("%s: got %+v, %v", token, got, err)
		}
	}
	// A user token minted for the BFF (same audience, user subject) and a token whose subject is not the audience's
	// client are refused.
	for _, token := range []string{"bff-user-token", "retail-forged-subject", "garbage"} {
		if _, err := v.Verify(ctx, token); !errors.Is(err, auth.ErrUnauthenticated) {
			t.Fatalf("%s must be refused, got %v", token, err)
		}
	}
}
