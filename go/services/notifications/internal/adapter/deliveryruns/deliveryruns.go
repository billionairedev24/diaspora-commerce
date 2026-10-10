// Package deliveryruns reads pre-order audiences from delivery-runs-service with a client-credentials token
// (client notifications-service, scope delivery-runs:audience.read).
//
// Endpoint (expected in delivery-runs; not yet in contracts/openapi/delivery-runs.yaml):
//
//	GET /v1/service/preorders/{windowId}/audience?channel=EMAIL|SMS|PUSH&segment=SUBSCRIBERS|WAITLIST&after=&limit=
//	→ {"members": [{"subscriptionId", "userId", "email", "phone", "unsubscribeUrl"}], "next": "<cursor or empty>"}
//
// SUBSCRIBERS are the area subscribers with consent on the channel plus past buyers in the window's targets (each
// person once); WAITLIST are the waitlist sign-ups of the window's areas (excluded from SUBSCRIBERS). 404 means the
// window is unknown (domain.ErrRecipientNotFound); every other failure is transient.
package deliveryruns

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Config configures the delivery-runs client.
type Config struct {
	BaseURL      string // e.g. http://delivery-runs
	TokenURL     string // <issuer>/oauth2/token
	ClientID     string
	ClientSecret string
}

// Scope is the client-credentials scope the audience route needs.
const Scope = "delivery-runs:audience.read"

type audienceImpl struct {
	base   string
	client *http.Client
}

var _ port.PreorderAudience = (*audienceImpl)(nil)

// New returns the PreorderAudience over delivery-runs. httpClient carries the timeout for token and API calls.
func New(cfg Config, httpClient *http.Client) port.PreorderAudience {
	cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
		Scopes: []string{Scope}, AuthStyle: oauth2.AuthStyleInHeader}
	tokenCtx := context.WithValue(context.Background(), oauth2.HTTPClient, httpClient)
	authed := cc.Client(tokenCtx)
	authed.Timeout = httpClient.Timeout
	return &audienceImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: authed}
}

// NewWithClient returns the PreorderAudience over an already authenticated client (tests, sidecar auth).
func NewWithClient(baseURL string, httpClient *http.Client) port.PreorderAudience {
	return &audienceImpl{base: strings.TrimRight(baseURL, "/"), client: httpClient}
}

func (a *audienceImpl) Audience(ctx context.Context, windowID string, channel domain.Channel, segment, after string, limit int) (port.AudiencePage, error) {
	q := url.Values{"channel": {strings.ToUpper(string(channel))}, "segment": {segment}}
	if after != "" {
		q.Set("after", after)
	}
	if limit > 0 {
		q.Set("limit", strconv.Itoa(limit))
	}
	path := "/v1/service/preorders/" + url.PathEscape(windowID) + "/audience"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.base+path+"?"+q.Encode(), nil)
	if err != nil {
		return port.AudiencePage{}, fmt.Errorf("delivery-runs request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := a.client.Do(req)
	if err != nil {
		return port.AudiencePage{}, fmt.Errorf("delivery-runs %s: %w", path, err)
	}
	defer func() { _ = res.Body.Close() }()
	switch {
	case res.StatusCode == http.StatusNotFound:
		_, _ = io.Copy(io.Discard, res.Body)
		return port.AudiencePage{}, domain.ErrRecipientNotFound
	case res.StatusCode != http.StatusOK:
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return port.AudiencePage{}, fmt.Errorf("delivery-runs %s: status %d: %s", path, res.StatusCode, strings.TrimSpace(string(body)))
	}
	var page port.AudiencePage
	if err := json.NewDecoder(io.LimitReader(res.Body, 8<<20)).Decode(&page); err != nil {
		return port.AudiencePage{}, fmt.Errorf("delivery-runs %s: decode: %w", path, err)
	}
	return page, nil
}
