// Package identity resolves notification recipients through identity-service with a client-credentials token
// (client notifications-service, scope users:read).
//
// Endpoints (planned in identity; not yet in contracts/openapi/identity.yaml):
//
//	GET /v1/internal/users/{userId}/contact → contactResponse
//	GET /v1/internal/orgs/{orgId}/owners    → {"orgId": "...", "ownerUserIds": ["..."]}
//
// 404 means the user or organisation is unknown (domain.ErrRecipientNotFound); every other failure is transient.
package identity

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Config configures the identity client.
type Config struct {
	BaseURL      string // e.g. http://identity
	TokenURL     string // <issuer>/oauth2/token
	ClientID     string
	ClientSecret string
	Scopes       []string
}

type resolverImpl struct {
	base   string
	client *http.Client
}

var _ port.RecipientResolver = (*resolverImpl)(nil)

// New returns the identity RecipientResolver. httpClient carries the timeout for both token and API calls; the
// token is cached and refreshed before it expires.
func New(cfg Config, httpClient *http.Client) port.RecipientResolver {
	cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
		Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
	tokenCtx := context.WithValue(context.Background(), oauth2.HTTPClient, httpClient)
	authed := cc.Client(tokenCtx)
	authed.Timeout = httpClient.Timeout
	return &resolverImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: authed}
}

// contactResponse is identity's view of how to reach a user.
type contactResponse struct {
	UserID        string             `json:"userId"`
	FirstName     string             `json:"firstName"`
	Email         string             `json:"email"`
	Phone         string             `json:"phone"`
	PhoneVerified *bool              `json:"phoneVerified"`
	Locale        string             `json:"locale"`
	TimeZone      string             `json:"timeZone"`
	Market        string             `json:"market"`
	PushTokens    []domain.PushToken `json:"pushTokens"`
	// Preferences are account-level channel switches (missing = on).
	Preferences map[string]bool `json:"preferences"`
}

func (r *resolverImpl) Contact(ctx context.Context, userID string) (port.Contact, error) {
	var resp contactResponse
	if err := r.get(ctx, "/v1/internal/users/"+url.PathEscape(userID)+"/contact", &resp); err != nil {
		return port.Contact{}, err
	}
	c := port.Contact{UserID: userID, FirstName: resp.FirstName, Email: resp.Email, Phone: resp.Phone,
		PushTokens: resp.PushTokens, Locale: resp.Locale, TimeZone: resp.TimeZone, Market: strings.ToLower(resp.Market)}
	if resp.PhoneVerified != nil && !*resp.PhoneVerified {
		c.Phone = "" // never text an unconfirmed number
	}
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush, domain.ChannelChat} {
		if on, ok := resp.Preferences[string(ch)]; ok && !on {
			c.Disabled = append(c.Disabled, ch)
		}
	}
	return c, nil
}

func (r *resolverImpl) OrgOwners(ctx context.Context, orgID string) ([]string, error) {
	var resp struct {
		OwnerUserIDs []string `json:"ownerUserIds"`
	}
	if err := r.get(ctx, "/v1/internal/orgs/"+url.PathEscape(orgID)+"/owners", &resp); err != nil {
		return nil, err
	}
	return resp.OwnerUserIDs, nil
}

func (r *resolverImpl) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.base+path, nil)
	if err != nil {
		return fmt.Errorf("identity request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := r.client.Do(req)
	if err != nil {
		return fmt.Errorf("identity %s: %w", path, err)
	}
	defer func() { _ = res.Body.Close() }()
	switch {
	case res.StatusCode == http.StatusNotFound:
		_, _ = io.Copy(io.Discard, res.Body)
		return domain.ErrRecipientNotFound
	case res.StatusCode != http.StatusOK:
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return fmt.Errorf("identity %s: status %d: %s", path, res.StatusCode, strings.TrimSpace(string(body)))
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(out); err != nil {
		return fmt.Errorf("identity %s: decode: %w", path, err)
	}
	return nil
}
