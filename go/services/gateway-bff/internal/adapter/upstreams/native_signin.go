package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// firstPartyScope is the client-credentials scope identity requires for its first-party sign-in API.
const firstPartyScope = "auth:first-party"

type nativeSignInImpl struct {
	c      httpclient.Client
	tokens port.ServiceTokens
}

var _ port.NativeSignInClient = (*nativeSignInImpl)(nil)

// NewNativeSignIn returns the client of identity's /v1/auth/* first-party sign-in API and /oauth2/authorize.
func NewNativeSignIn(c httpclient.Client, tokens port.ServiceTokens) port.NativeSignInClient {
	return &nativeSignInImpl{c: c, tokens: tokens}
}

func (n *nativeSignInImpl) post(ctx context.Context, path string, body map[string]any, out any) error {
	bearer, err := n.tokens.Token(ctx, firstPartyScope)
	if err != nil {
		return err
	}
	_, err = call(ctx, n.c, httpclient.Request{Method: http.MethodPost, Path: path, Bearer: bearer, JSON: body}, out)
	return err
}

func whoBody(who port.NativeWho, extra map[string]any) map[string]any {
	ip := who.IP
	if ip == "" {
		ip = "127.0.0.1"
	}
	body := map[string]any{"clientIp": ip, "appClientId": who.AppClientID}
	if who.UserAgent != "" {
		body["userAgent"] = who.UserAgent
	}
	for k, v := range extra {
		body[k] = v
	}
	return body
}

func (n *nativeSignInImpl) Password(ctx context.Context, email, password, trustedDeviceToken string, who port.NativeWho) (port.NativeStep, error) {
	extra := map[string]any{"email": email, "password": password}
	if trustedDeviceToken != "" {
		extra["trustedDeviceToken"] = trustedDeviceToken
	}
	var out port.NativeStep
	err := n.post(ctx, "/v1/auth/password", whoBody(who, extra), &out)
	return out, err
}

func (n *nativeSignInImpl) SendSMSCode(ctx context.Context, challengeID string, who port.NativeWho) (port.NativeSMSSent, error) {
	var out port.NativeSMSSent
	err := n.post(ctx, "/v1/auth/mfa/sms", whoBody(who, map[string]any{"challengeId": challengeID}), &out)
	return out, err
}

func (n *nativeSignInImpl) VerifyFactor(ctx context.Context, challengeID, factor, code string, trustDevice bool, who port.NativeWho) (port.NativeStep, error) {
	var out port.NativeStep
	err := n.post(ctx, "/v1/auth/mfa/verify", whoBody(who, map[string]any{"challengeId": challengeID, "factor": factor,
		"code": code, "trustDevice": trustDevice}), &out)
	return out, err
}

func (n *nativeSignInImpl) Authorize(ctx context.Context, in port.NativeAuthorize) (string, error) {
	res, err := call(ctx, n.c, httpclient.Request{Method: http.MethodGet, Path: "/oauth2/authorize", Query: url.Values{
		"response_type": {"code"}, "client_id": {in.ClientID}, "redirect_uri": {in.RedirectURI}, "scope": {in.Scope},
		"code_challenge": {in.CodeChallenge}, "code_challenge_method": {"S256"}, "state": {in.State},
		"login_ticket": {in.Ticket}}}, nil)
	if err != nil {
		return "", err
	}
	loc, err := url.Parse(res.Header.Get("Location"))
	if err != nil || res.Header.Get("Location") == "" {
		return "", fmt.Errorf("identity authorize: no redirect (status %d)", res.Status)
	}
	q := loc.Query()
	if e := q.Get("error"); e != "" {
		return "", &port.UpstreamProblem{Upstream: "identity", Status: http.StatusBadRequest, Code: e, Detail: q.Get("error_description")}
	}
	code := q.Get("code")
	if code == "" {
		return "", fmt.Errorf("identity authorize: redirect without code")
	}
	return code, nil
}
