package upstreams

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// IdentityOptions configure the identity client (confidential OAuth client gateway-bff).
type IdentityOptions struct {
	ClientID     string
	ClientSecret string
	ProvidersTTL time.Duration
	Now          func() time.Time
}

type identityImpl struct {
	c     httpclient.Client
	o     IdentityOptions
	cache port.Cache
}

var _ port.IdentityClient = (*identityImpl)(nil)

// NewIdentity returns the identity client: /oauth2/token, /oauth2/revoke and the /v1 REST API.
func NewIdentity(c httpclient.Client, cache port.Cache, o IdentityOptions) port.IdentityClient {
	if o.Now == nil {
		o.Now = time.Now
	}
	return &identityImpl{c: c, o: o, cache: cache}
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	IDToken      string `json:"id_token"`
	ExpiresIn    int    `json:"expires_in"`
}

func (i *identityImpl) token(ctx context.Context, form url.Values) (domain.Tokens, error) {
	var tr tokenResponse
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/oauth2/token", Form: form,
		BasicUser: i.o.ClientID, BasicPassword: i.o.ClientSecret}, &tr)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && (p.Code == "invalid_grant" || p.Status == http.StatusBadRequest || p.Status == http.StatusUnauthorized) {
			return domain.Tokens{}, fmt.Errorf("token: %w", port.ErrInvalidGrant)
		}
		return domain.Tokens{}, err
	}
	if tr.AccessToken == "" {
		return domain.Tokens{}, errors.New("identity: token response without access_token")
	}
	exp := tr.ExpiresIn
	if exp <= 0 {
		exp = 300
	}
	return domain.Tokens{AccessToken: tr.AccessToken, RefreshToken: tr.RefreshToken, IDToken: tr.IDToken,
		ExpiresAt: i.o.Now().Add(time.Duration(exp) * time.Second)}, nil
}

func (i *identityImpl) ExchangeCode(ctx context.Context, code, verifier, redirectURI string) (domain.Tokens, error) {
	return i.token(ctx, url.Values{"grant_type": {"authorization_code"}, "code": {code}, "code_verifier": {verifier},
		"redirect_uri": {redirectURI}})
}

func (i *identityImpl) Refresh(ctx context.Context, refreshToken string) (domain.Tokens, error) {
	return i.token(ctx, url.Values{"grant_type": {"refresh_token"}, "refresh_token": {refreshToken}})
}

func (i *identityImpl) Revoke(ctx context.Context, token, hint string) error {
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/oauth2/revoke",
		Form: url.Values{"token": {token}, "token_type_hint": {hint}}, BasicUser: i.o.ClientID, BasicPassword: i.o.ClientSecret}, nil)
	return err
}

func (i *identityImpl) Me(ctx context.Context, bearer string) (port.IdentityMe, error) {
	var me port.IdentityMe
	_, err := call(ctx, i.c, httpclient.Request{Path: "/v1/me", Bearer: bearer}, &me)
	return me, err
}

func (i *identityImpl) SignUp(ctx context.Context, req port.IdentitySignUp) error {
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/accounts", JSON: req}, nil)
	return err
}

func (i *identityImpl) StartPhoneVerification(ctx context.Context, bearer string) (port.PhoneCodeSent, error) {
	var out port.PhoneCodeSent
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/me/phone/verification", Bearer: bearer}, &out)
	return out, err
}

func (i *identityImpl) ConfirmPhoneVerification(ctx context.Context, bearer, code string) (port.PhoneVerification, error) {
	var out port.PhoneVerification
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/me/phone/verification/confirm", Bearer: bearer,
		JSON: map[string]string{"code": code}}, &out)
	return out, err
}

func (i *identityImpl) VerifyEmail(ctx context.Context, email, code string) error {
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/accounts/verification",
		JSON: map[string]string{"email": email, "code": code}}, nil)
	return err
}

func (i *identityImpl) ResendCode(ctx context.Context, email string) error {
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/accounts/verification/resend",
		JSON: map[string]string{"email": email}}, nil)
	return err
}

func (i *identityImpl) Providers(ctx context.Context) ([]port.IdentityProvider, error) {
	return cached(i.cache, "identity:providers", func() ([]port.IdentityProvider, http.Header, error) {
		var out []port.IdentityProvider
		res, err := call(ctx, i.c, httpclient.Request{Path: "/v1/identity-providers"}, &out)
		if err != nil {
			return nil, nil, err
		}
		return out, res.Header, nil
	}, func(http.Header) time.Duration { return i.o.ProvidersTTL })
}

func (i *identityImpl) Addresses(ctx context.Context, bearer string) ([]port.IdentityAddress, error) {
	res, err := call(ctx, i.c, httpclient.Request{Path: "/v1/me/addresses", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.IdentityAddress](res.Body)
	return items, err
}

func (i *identityImpl) CreateAddress(ctx context.Context, bearer string, a port.IdentityAddress) (port.IdentityAddress, error) {
	var out port.IdentityAddress
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/me/addresses", Bearer: bearer, JSON: a}, &out)
	return out, err
}

func (i *identityImpl) UpdateAddress(ctx context.Context, bearer, id string, a port.IdentityAddress) (port.IdentityAddress, error) {
	var out port.IdentityAddress
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPatch, Path: "/v1/me/addresses/" + url.PathEscape(id), Bearer: bearer, JSON: a}, &out)
	return out, err
}

func (i *identityImpl) DeleteAddress(ctx context.Context, bearer, id string) error {
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodDelete, Path: "/v1/me/addresses/" + url.PathEscape(id), Bearer: bearer}, nil)
	return err
}

func (i *identityImpl) DefaultAddress(ctx context.Context, bearer, id string) (port.IdentityAddress, error) {
	var out port.IdentityAddress
	_, err := call(ctx, i.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/me/addresses/" + url.PathEscape(id) + "/default", Bearer: bearer}, &out)
	return out, err
}

// ---------------------------------------------------------------- token verification (JWKS)

type verifierImpl struct {
	access auth.Verifier
	id     auth.Verifier
}

var _ port.TokenVerifier = (*verifierImpl)(nil)

// NewTokenVerifier verifies app bearer tokens (audience optional) and ID tokens (audience = the BFF client id)
// against identity's JWKS.
func NewTokenVerifier(issuer, accessAudience, clientID string, client *http.Client) port.TokenVerifier {
	return &verifierImpl{
		access: auth.NewJWKSVerifier(issuer, accessAudience, client),
		id:     auth.NewJWKSVerifier(issuer, clientID, client),
	}
}

func (v *verifierImpl) VerifyAccess(ctx context.Context, token string) (string, []string, error) {
	a, err := v.access.Verify(ctx, token)
	if err != nil {
		return "", nil, fmt.Errorf("verify access token: %w", err)
	}
	return a.Subject, a.Roles, nil
}

func (v *verifierImpl) VerifyIDToken(ctx context.Context, token, nonce string) (domain.Profile, error) {
	a, err := v.id.Verify(ctx, token)
	if err != nil {
		return domain.Profile{}, fmt.Errorf("verify id token: %w", err)
	}
	// The signature, issuer, audience and expiry are verified above; read the remaining OIDC claims.
	claims := jwt.MapClaims{}
	if _, _, err := jwt.NewParser().ParseUnverified(token, claims); err != nil {
		return domain.Profile{}, fmt.Errorf("parse id token: %w", err)
	}
	str := func(k string) string {
		s, _ := claims[k].(string)
		return s
	}
	if nonce != "" && str("nonce") != nonce {
		return domain.Profile{}, errors.New("id token nonce mismatch")
	}
	return domain.Profile{Subject: a.Subject, FirstName: str("given_name"), LastName: str("family_name"),
		Email: str("email"), Roles: a.Roles}, nil
}
