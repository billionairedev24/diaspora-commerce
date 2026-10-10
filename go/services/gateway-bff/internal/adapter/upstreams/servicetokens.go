package upstreams

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type serviceTokensImpl struct {
	c  httpclient.Client
	o  IdentityOptions
	mu sync.Mutex
	by map[string]struct {
		token string
		exp   time.Time
	}
}

var _ port.ServiceTokens = (*serviceTokensImpl)(nil)

// NewServiceTokens returns the gateway-bff client-credentials token source (identity /oauth2/token), cached per scope
// until 30 s before expiry.
func NewServiceTokens(c httpclient.Client, o IdentityOptions) port.ServiceTokens {
	if o.Now == nil {
		o.Now = time.Now
	}
	return &serviceTokensImpl{c: c, o: o, by: map[string]struct {
		token string
		exp   time.Time
	}{}}
}

func (s *serviceTokensImpl) Token(ctx context.Context, scope string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.by[scope]; ok && s.o.Now().Before(t.exp) {
		return t.token, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}}
	if scope != "" {
		form.Set("scope", scope)
	}
	var tr tokenResponse
	if _, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: "/oauth2/token", Form: form,
		BasicUser: s.o.ClientID, BasicPassword: s.o.ClientSecret}, &tr); err != nil {
		return "", fmt.Errorf("service token: %w", err)
	}
	if tr.AccessToken == "" {
		return "", errors.New("identity: client-credentials response without access_token")
	}
	s.by[scope] = struct {
		token string
		exp   time.Time
	}{tr.AccessToken, s.o.Now().Add(time.Duration(max(tr.ExpiresIn, 60))*time.Second - 30*time.Second)}
	return tr.AccessToken, nil
}
