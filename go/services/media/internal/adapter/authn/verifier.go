// Package authn verifies the bearer tokens media accepts: identity tokens issued for media's audience, and the
// client-credentials tokens of the service clients the purpose rules name (identity issues those with the client id
// as audience and subject, and principal type SERVICE).
package authn

import (
	"context"
	"fmt"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
)

type serviceClient struct {
	id       string
	verifier auth.Verifier
}

type verifierImpl struct {
	media   auth.Verifier
	clients []serviceClient
}

var _ auth.Verifier = (*verifierImpl)(nil)

// NewVerifier accepts tokens for audience, plus each listed client's own client-credentials token.
func NewVerifier(issuer, audience string, clients []string, client *http.Client) auth.Verifier {
	verifiers := make([]auth.Verifier, 0, len(clients))
	for _, id := range clients {
		verifiers = append(verifiers, auth.NewJWKSVerifier(issuer, id, client))
	}
	return newVerifier(auth.NewJWKSVerifier(issuer, audience, client), clients, verifiers)
}

func newVerifier(media auth.Verifier, ids []string, verifiers []auth.Verifier) *verifierImpl {
	v := &verifierImpl{media: media}
	for i, id := range ids {
		v.clients = append(v.clients, serviceClient{id: id, verifier: verifiers[i]})
	}
	return v
}

func (v *verifierImpl) Verify(ctx context.Context, token string) (auth.Actor, error) {
	actor, err := v.media.Verify(ctx, token)
	if err == nil {
		return actor, nil
	}
	for _, c := range v.clients {
		a, cerr := c.verifier.Verify(ctx, token)
		// Only the client's own service token: a user token obtained through the same client (for example the
		// BFF's sign-in) carries the client as audience too, but a user as subject.
		if cerr == nil && a.Type == "SERVICE" && a.Subject == c.id {
			return a, nil
		}
	}
	return auth.Actor{}, fmt.Errorf("verify token: %w", err)
}
