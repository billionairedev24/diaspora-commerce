package upstreams

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// FirstPartyScope is the client-credentials scope identity requires on /v1/auth/*.
const FirstPartyScope = "auth:first-party"

type firstPartyImpl struct {
	c   httpclient.Client
	o   IdentityOptions
	mu  sync.Mutex
	tok string
	exp time.Time
}

var _ port.FirstPartyClient = (*firstPartyImpl)(nil)

// NewFirstParty returns the identity first-party sign-in client. It holds the gateway-bff client-credentials token
// (scope auth:first-party), cached until 30 s before it expires and fetched again once after a 401.
func NewFirstParty(c httpclient.Client, o IdentityOptions) port.FirstPartyClient {
	if o.Now == nil {
		o.Now = time.Now
	}
	return &firstPartyImpl{c: c, o: o}
}

func (f *firstPartyImpl) token(ctx context.Context, fresh bool) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !fresh && f.tok != "" && f.o.Now().Before(f.exp) {
		return f.tok, nil
	}
	var tr tokenResponse
	if _, err := call(ctx, f.c, httpclient.Request{Method: http.MethodPost, Path: "/oauth2/token",
		Form:      url.Values{"grant_type": {"client_credentials"}, "scope": {FirstPartyScope}},
		BasicUser: f.o.ClientID, BasicPassword: f.o.ClientSecret}, &tr); err != nil {
		return "", fmt.Errorf("first-party token: %w", err)
	}
	if tr.AccessToken == "" {
		return "", errors.New("identity: client-credentials response without access_token")
	}
	exp := max(tr.ExpiresIn, 60)
	f.tok, f.exp = tr.AccessToken, f.o.Now().Add(time.Duration(exp)*time.Second-30*time.Second)
	return f.tok, nil
}

// post calls a /v1/auth/* endpoint with the service token, refreshing it once when identity answers 401 without a
// sign-in problem code (an expired or revoked service token).
func (f *firstPartyImpl) post(ctx context.Context, path string, body, out any) error {
	for attempt := range 2 {
		tok, err := f.token(ctx, attempt > 0)
		if err != nil {
			return err
		}
		_, err = call(ctx, f.c, httpclient.Request{Method: http.MethodPost, Path: path, Bearer: tok, JSON: body}, out)
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnauthorized && p.Code == "" && attempt == 0 {
			continue
		}
		return err
	}
	return nil
}

func (f *firstPartyImpl) Password(ctx context.Context, cc port.ClientContext, email, password, trusted string) (port.FirstPartyStep, error) {
	body := map[string]any{"email": email, "password": password, "clientIp": cc.IP, "userAgent": cc.UserAgent}
	if trusted != "" {
		body["trustedDeviceToken"] = trusted
	}
	var out port.FirstPartyStep
	return out, f.post(ctx, "/v1/auth/password", body, &out)
}

func (f *firstPartyImpl) SendSMSCode(ctx context.Context, cc port.ClientContext, challengeID string) (string, error) {
	var out struct {
		SentTo string `json:"sentTo"`
	}
	err := f.post(ctx, "/v1/auth/mfa/sms", map[string]any{"challengeId": challengeID, "clientIp": cc.IP}, &out)
	return out.SentTo, err
}

func (f *firstPartyImpl) VerifyMFA(ctx context.Context, cc port.ClientContext, challengeID, factor, code string, trust bool) (port.FirstPartyStep, error) {
	var out port.FirstPartyStep
	return out, f.post(ctx, "/v1/auth/mfa/verify", map[string]any{"challengeId": challengeID, "factor": factor, "code": code,
		"trustDevice": trust, "clientIp": cc.IP, "userAgent": cc.UserAgent}, &out)
}

func (f *firstPartyImpl) PasskeyOptions(ctx context.Context, cc port.ClientContext) (port.PasskeyChallenge, error) {
	var out port.PasskeyChallenge
	return out, f.post(ctx, "/v1/auth/passkey/options", map[string]any{"clientIp": cc.IP, "userAgent": cc.UserAgent}, &out)
}

func (f *firstPartyImpl) PasskeyVerify(ctx context.Context, cc port.ClientContext, challengeID string, credential json.RawMessage) (port.FirstPartyStep, error) {
	var out port.FirstPartyStep
	return out, f.post(ctx, "/v1/auth/passkey/verify", map[string]any{"challengeId": challengeID, "credential": credential,
		"clientIp": cc.IP, "userAgent": cc.UserAgent}, &out)
}

func (f *firstPartyImpl) RedeemTicket(ctx context.Context, q url.Values) (string, string, error) {
	res, err := call(ctx, f.c, httpclient.Request{Path: "/oauth2/authorize", Query: q}, nil)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusBadRequest {
			return "", "", fmt.Errorf("redeem login ticket: %w", port.ErrInvalidGrant)
		}
		return "", "", fmt.Errorf("redeem login ticket: %w", err)
	}
	loc, perr := url.Parse(res.Header.Get("Location"))
	if res.Status != http.StatusFound && res.Status != http.StatusSeeOther || perr != nil {
		return "", "", fmt.Errorf("redeem login ticket: unexpected answer %d", res.Status)
	}
	lq := loc.Query()
	if e := lq.Get("error"); e != "" {
		return "", "", fmt.Errorf("redeem login ticket: %s: %w", e, port.ErrInvalidGrant)
	}
	if lq.Get("code") == "" {
		return "", "", errors.New("redeem login ticket: redirect without code")
	}
	return lq.Get("code"), lq.Get("state"), nil
}
