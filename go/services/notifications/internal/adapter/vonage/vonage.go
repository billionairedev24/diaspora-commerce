// Package vonage implements port.SmsSender with the Vonage (Nexmo) SMS API.
package vonage

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "vonage"

// DefaultBaseURL is the Vonage SMS API.
const DefaultBaseURL = "https://rest.nexmo.com"

// Config configures the adapter. APIKey/APISecret come from VONAGE_API_KEY / VONAGE_API_SECRET.
type Config struct {
	APIKey      string
	APISecret   string
	From        string
	CallbackURL string
	BaseURL     string
}

type senderImpl struct {
	cfg    Config
	client *http.Client
}

var _ port.SmsSender = (*senderImpl)(nil)

// New returns the Vonage SMS sender.
func New(cfg Config, client *http.Client) port.SmsSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, client: client}
}

func (s *senderImpl) Name() string { return Name }

// Vonage returns HTTP 200 with a per-message status; these statuses are worth retrying or failing over:
// 1 throttled, 4 invalid credentials, 5 internal error, 8 account barred, 9 partner quota exceeded.
var retryableStatus = map[string]bool{"1": true, "4": true, "5": true, "8": true, "9": true}

func isASCII(s string) bool {
	for _, r := range s {
		if r > unicode.MaxASCII {
			return false
		}
	}
	return true
}

func (s *senderImpl) Send(ctx context.Context, msg domain.SmsMessage) (domain.Receipt, error) {
	form := url.Values{
		"api_key":    {s.cfg.APIKey},
		"api_secret": {s.cfg.APISecret},
		"from":       {s.cfg.From},
		"to":         {strings.TrimPrefix(msg.To, "+")},
		"text":       {msg.Body},
		"client-ref": {msg.NotificationID},
	}
	if !isASCII(msg.Body) {
		form.Set("type", "unicode")
	}
	if s.cfg.CallbackURL != "" {
		form.Set("callback", s.cfg.CallbackURL)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.BaseURL+"/sms/json", strings.NewReader(form.Encode()))
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	if !res.OK() {
		return domain.Receipt{}, providerhttp.Classify(Name, res, "").Wrap(res.Detail())
	}
	var parsed struct {
		Messages []struct {
			MessageID string `json:"message-id"`
			Status    string `json:"status"`
			ErrorText string `json:"error-text"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(res.Body, &parsed); err != nil || len(parsed.Messages) == 0 {
		return domain.Receipt{}, domain.Retry(Name, res.Status, "decode").Wrap(errors.New("unexpected response"))
	}
	m := parsed.Messages[0]
	if m.Status != "0" {
		if retryableStatus[m.Status] {
			return domain.Receipt{}, domain.Retry(Name, res.Status, m.Status).Wrap(errors.New(m.ErrorText))
		}
		return domain.Receipt{}, domain.Permanent(Name, res.Status, m.Status).Wrap(errors.New(m.ErrorText))
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: m.MessageID, AcceptedAt: time.Now().UTC()}, nil
}
