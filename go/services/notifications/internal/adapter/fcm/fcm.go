// Package fcm implements port.PushSender with the Firebase Cloud Messaging HTTP v1 API. Access tokens come
// from an OAuth2 token source built from the service-account JSON (golang.org/x/oauth2/google).
package fcm

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "fcm"

// DefaultBaseURL is the FCM API.
const DefaultBaseURL = "https://fcm.googleapis.com"

// Scope is the OAuth2 scope for sending messages.
const Scope = "https://www.googleapis.com/auth/firebase.messaging"

// Config configures the adapter.
type Config struct {
	ProjectID string
	BaseURL   string
}

type senderImpl struct {
	cfg    Config
	tokens oauth2.TokenSource
	client *http.Client
}

var _ port.PushSender = (*senderImpl)(nil)

// New returns the FCM push sender.
func New(cfg Config, tokens oauth2.TokenSource, client *http.Client) port.PushSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, tokens: tokens, client: client}
}

// TokenSourceFromJSON builds a token source (and the project id) from FCM_SERVICE_ACCOUNT_JSON.
func TokenSourceFromJSON(ctx context.Context, serviceAccountJSON []byte) (oauth2.TokenSource, string, error) {
	creds, err := google.CredentialsFromJSONWithType(ctx, serviceAccountJSON, google.ServiceAccount, Scope)
	if err != nil {
		return nil, "", fmt.Errorf("fcm credentials: %w", err)
	}
	return creds.TokenSource, creds.ProjectID, nil
}

func (s *senderImpl) Name() string { return Name }

func (s *senderImpl) Accepts(p domain.PushPlatform) bool { return p == domain.PushFCM }

type notification struct {
	Title string `json:"title,omitempty"`
	Body  string `json:"body,omitempty"`
}

type message struct {
	Token        string            `json:"token"`
	Notification notification      `json:"notification"`
	Data         map[string]string `json:"data,omitempty"`
	Android      map[string]any    `json:"android,omitempty"`
}

func (s *senderImpl) Send(ctx context.Context, msg domain.PushMessage) (domain.Receipt, error) {
	tok, err := s.tokens.Token()
	if err != nil {
		return domain.Receipt{}, domain.Retry(Name, 0, "oauth2").Wrap(err)
	}
	body := map[string]message{"message": {
		Token:        msg.Token.Token,
		Notification: notification{Title: msg.Title, Body: msg.Body},
		Data:         msg.Data,
		Android:      map[string]any{"priority": "high"},
	}}
	req, err := providerhttp.NewJSONRequest(ctx, fmt.Sprintf("%s/v1/projects/%s/messages:send", s.cfg.BaseURL, s.cfg.ProjectID), body)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Authorization", "Bearer "+tok.AccessToken)
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	if !res.OK() {
		var e struct {
			Error struct {
				Status string `json:"status"`
			} `json:"error"`
		}
		_ = json.Unmarshal(res.Body, &e)
		return domain.Receipt{}, providerhttp.Classify(Name, res, e.Error.Status).Wrap(res.Detail())
	}
	var ok struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal(res.Body, &ok)
	return domain.Receipt{Provider: Name, ProviderMessageID: ok.Name, AcceptedAt: time.Now().UTC()}, nil
}
