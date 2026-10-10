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
	// Without forwarded audiences a user token minted for the BFF (same audience, user subject) is refused, as is a
	// token whose subject is not the audience's client.
	for _, token := range []string{"bff-user-token", "retail-forged-subject", "garbage"} {
		if _, err := v.Verify(ctx, token); !errors.Is(err, auth.ErrUnauthenticated) {
			t.Fatalf("%s must be refused, got %v", token, err)
		}
	}
}

func TestAcceptsUserTokensForwardedByTheBFF(t *testing.T) {
	seller := auth.Actor{Subject: "user-7", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	staff := auth.Actor{Subject: "staff-2", Type: "STAFF"}
	otherService := auth.Actor{Subject: "orders-service", Type: "SERVICE"}
	bffAudience := stubVerifier{"bff-seller": seller, "bff-staff": staff, "bff-other-service": otherService,
		"bff-no-subject": {Type: "CUSTOMER"}}
	v := newVerifier(stubVerifier{}, nil, nil, bffAudience)
	ctx := context.Background()
	for token, want := range map[string]auth.Actor{"bff-seller": seller, "bff-staff": staff} {
		got, err := v.Verify(ctx, token)
		if err != nil || got.Subject != want.Subject || got.Type != want.Type || got.OrgID != want.OrgID {
			t.Fatalf("%s: got %+v, %v", token, got, err)
		}
	}
	for _, token := range []string{"bff-other-service", "bff-no-subject", "garbage"} {
		if _, err := v.Verify(ctx, token); !errors.Is(err, auth.ErrUnauthenticated) {
			t.Fatalf("%s must be refused, got %v", token, err)
		}
	}
}
