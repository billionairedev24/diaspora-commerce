// Package deliveryruns reads audiences from delivery-runs-service with a client-credentials token (client
// notifications-service, scope delivery-runs:audience.read).
//
// Pre-order windows (expected in delivery-runs; not yet in contracts/openapi/delivery-runs.yaml):
//
//	GET /v1/service/preorders/{windowId}/audience?channel=EMAIL|SMS|PUSH&segment=SUBSCRIBERS|WAITLIST&after=&limit=
//	→ {"members": [{"subscriptionId", "userId", "email", "phone", "unsubscribeUrl"}], "next": "<cursor or empty>"}
//
// SUBSCRIBERS are the area subscribers with consent on the channel plus past buyers in the window's targets (each
// person once); WAITLIST are the waitlist sign-ups of the window's areas (excluded from SUBSCRIBERS).
//
// Locations (S262, contracts/openapi/delivery-runs.yaml):
//
//	GET /v1/service/locations/{locationId}/audience?channel=EMAIL|SMS|PUSH|WHATSAPP&after=&limit=
//
// the subscribers with consent on the channel, keyset-paged. WhatsApp is channel chat here and WHATSAPP there.
//
// 404 means the window or location is unknown (domain.ErrRecipientNotFound); every other failure is transient.
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

// Audiences pages through who may receive a pre-order window's comms and a location's run alerts.
type Audiences interface {
	port.PreorderAudience
	port.LocationAudience
}

type audienceImpl struct {
	base   string
	client *http.Client
}

var _ Audiences = (*audienceImpl)(nil)

// New returns the audiences over delivery-runs. httpClient carries the timeout for token and API calls.
func New(cfg Config, httpClient *http.Client) Audiences {
	cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
		Scopes: []string{Scope}, AuthStyle: oauth2.AuthStyleInHeader}
	tokenCtx := context.WithValue(context.Background(), oauth2.HTTPClient, httpClient)
	authed := cc.Client(tokenCtx)
	authed.Timeout = httpClient.Timeout
	return &audienceImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: authed}
}

// NewWithClient returns the audiences over an already authenticated client (tests, sidecar auth).
func NewWithClient(baseURL string, httpClient *http.Client) Audiences {
	return &audienceImpl{base: strings.TrimRight(baseURL, "/"), client: httpClient}
}

func (a *audienceImpl) Audience(ctx context.Context, windowID string, channel domain.Channel, segment, after string, limit int) (port.AudiencePage, error) {
	q := url.Values{"channel": {channelName(channel)}, "segment": {segment}}
	setPaging(q, after, limit)
	return a.page(ctx, "/v1/service/preorders/"+url.PathEscape(windowID)+"/audience", q)
}

func (a *audienceImpl) LocationAudience(ctx context.Context, locationID string, channel domain.Channel, after string, limit int) (port.AudiencePage, error) {
	q := url.Values{"channel": {channelName(channel)}}
	setPaging(q, after, limit)
	return a.page(ctx, "/v1/service/locations/"+url.PathEscape(locationID)+"/audience", q)
}

// channelName is the channel as delivery-runs names it: WhatsApp is chat here and WHATSAPP there.
func channelName(ch domain.Channel) string {
	if ch == domain.ChannelChat {
		return "WHATSAPP"
	}
	return strings.ToUpper(string(ch))
}

func setPaging(q url.Values, after string, limit int) {
	if after != "" {
		q.Set("after", after)
	}
	if limit > 0 {
		q.Set("limit", strconv.Itoa(limit))
	}
}

func (a *audienceImpl) page(ctx context.Context, path string, q url.Values) (port.AudiencePage, error) {
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
