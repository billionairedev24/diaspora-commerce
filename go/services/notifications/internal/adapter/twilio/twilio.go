// Package twilio implements port.SmsSender with the Twilio Programmable Messaging API (form-encoded, basic auth).
package twilio

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "twilio"

// DefaultBaseURL is the Twilio REST API.
const DefaultBaseURL = "https://api.twilio.com"

// Config configures the adapter. AccountSID/AuthToken come from TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN.
type Config struct {
	AccountSID          string
	AuthToken           string
	From                string // sending number (used when MessagingServiceSID is empty)
	MessagingServiceSID string // 10DLC campaign's messaging service (preferred)
	StatusCallbackURL   string // public URL of POST /v1/webhooks/twilio/status
	BaseURL             string
}

type senderImpl struct {
	cfg    Config
	client *http.Client
}

var _ port.SmsSender = (*senderImpl)(nil)

// New returns the Twilio SMS sender.
func New(cfg Config, client *http.Client) port.SmsSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, client: client}
}

func (s *senderImpl) Name() string { return Name }

// permanentCodes are Twilio errors another provider cannot fix either (invalid or unsubscribed numbers).
var permanentCodes = map[int]bool{21211: true, 21610: true, 21614: true, 21408: true, 21612: true}

func (s *senderImpl) Send(ctx context.Context, msg domain.SmsMessage) (domain.Receipt, error) {
	form := url.Values{"To": {msg.To}, "Body": {msg.Body}}
	if s.cfg.MessagingServiceSID != "" {
		form.Set("MessagingServiceSid", s.cfg.MessagingServiceSID)
	} else {
		form.Set("From", s.cfg.From)
	}
	if s.cfg.StatusCallbackURL != "" {
		form.Set("StatusCallback", s.cfg.StatusCallbackURL)
	}
	endpoint := fmt.Sprintf("%s/2010-04-01/Accounts/%s/Messages.json", s.cfg.BaseURL, url.PathEscape(s.cfg.AccountSID))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	req.SetBasicAuth(s.cfg.AccountSID, s.cfg.AuthToken)
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	var parsed struct {
		SID     string `json:"sid"`
		Code    int    `json:"code"`
		Message string `json:"message"`
	}
	_ = json.Unmarshal(res.Body, &parsed)
	if !res.OK() {
		code := ""
		if parsed.Code != 0 {
			code = strconv.Itoa(parsed.Code)
		}
		if permanentCodes[parsed.Code] {
			return domain.Receipt{}, domain.Permanent(Name, res.Status, code).Wrap(fmt.Errorf("%s", parsed.Message))
		}
		return domain.Receipt{}, providerhttp.Classify(Name, res, code).Wrap(res.Detail())
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: parsed.SID, AcceptedAt: time.Now().UTC()}, nil
}

// MapStatus maps a Twilio MessageStatus to a notification status (ok=false for intermediate states).
func MapStatus(s string) (domain.Status, bool) {
	switch s {
	case "sent":
		return domain.StatusSent, true
	case "delivered", "read":
		return domain.StatusDelivered, true
	case "undelivered", "failed":
		return domain.StatusFailed, true
	default: // accepted, queued, sending, scheduled
		return "", false
	}
}
