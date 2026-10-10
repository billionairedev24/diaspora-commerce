// Package authn verifies the bearer tokens media accepts: identity tokens issued for media's audience, the
// client-credentials tokens of the service clients the purpose rules name (identity issues those with the client id
// as audience and subject, and principal type SERVICE), and user tokens forwarded by the BFF (S250): a signed-in
// user's access token minted for the gateway-bff audience, which the BFF relays as-is. Such a token acts as that
// user (seller, applicant, staff), so the purpose rules apply to the user, never to the BFF.
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
	media     auth.Verifier
	clients   []serviceClient
	forwarded []auth.Verifier
}

var _ auth.Verifier = (*verifierImpl)(nil)

// NewVerifier accepts tokens for audience, each listed client's own client-credentials token, and user (non-service)
// tokens of the forwarded audiences (gateway-bff).
func NewVerifier(issuer, audience string, clients, forwardedAudiences []string, client *http.Client) auth.Verifier {
	verifiers := make([]auth.Verifier, 0, len(clients))
	for _, id := range clients {
		verifiers = append(verifiers, auth.NewJWKSVerifier(issuer, id, client))
	}
	v := newVerifier(auth.NewJWKSVerifier(issuer, audience, client), clients, verifiers)
	for _, aud := range forwardedAudiences {
		v.forwarded = append(v.forwarded, auth.NewJWKSVerifier(issuer, aud, client))
	}
	return v
}

func newVerifier(media auth.Verifier, ids []string, verifiers []auth.Verifier, forwarded ...auth.Verifier) *verifierImpl {
	v := &verifierImpl{media: media, forwarded: forwarded}
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
	for _, f := range v.forwarded {
		// A user's token relayed by the BFF; another service's token for that audience is not a user.
		if a, ferr := f.Verify(ctx, token); ferr == nil && a.Type != "SERVICE" && a.Subject != "" {
			return a, nil
		}
	}
	return auth.Actor{}, fmt.Errorf("verify token: %w", err)
}
