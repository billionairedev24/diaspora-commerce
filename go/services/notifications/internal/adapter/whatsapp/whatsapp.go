// Package whatsapp implements port.ChatSender with the WhatsApp Business Cloud API (Meta Graph API).
package whatsapp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "whatsapp"

// DefaultBaseURL is the Graph API.
const DefaultBaseURL = "https://graph.facebook.com"

// Config configures the adapter. Token comes from WHATSAPP_TOKEN.
type Config struct {
	PhoneNumberID string
	Token         string
	APIVersion    string
	BaseURL       string
}

type senderImpl struct {
	cfg    Config
	client *http.Client
}

var _ port.ChatSender = (*senderImpl)(nil)

// New returns the WhatsApp sender.
func New(cfg Config, client *http.Client) port.ChatSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	if cfg.APIVersion == "" {
		cfg.APIVersion = "v21.0"
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, client: client}
}

func (s *senderImpl) Name() string { return Name }

// retryableCodes: 4/80007/130429 rate limits, 131048 spam rate limit, 131056 pair rate limit, 190 expired token,
// 131000 generic internal error.
var retryableCodes = map[int]bool{4: true, 80007: true, 130429: true, 131048: true, 131056: true, 190: true, 131000: true}

func (s *senderImpl) Send(ctx context.Context, msg domain.ChatMessage) (domain.Receipt, error) {
	body := map[string]any{
		"messaging_product": "whatsapp",
		"recipient_type":    "individual",
		"to":                strings.TrimPrefix(msg.To, "+"),
	}
	if msg.TemplateName != "" {
		params := make([]map[string]string, 0, len(msg.Params))
		for _, p := range msg.Params {
			params = append(params, map[string]string{"type": "text", "text": p})
		}
		lang := msg.Language
		if lang == "" {
			lang = "en"
		}
		tpl := map[string]any{"name": msg.TemplateName, "language": map[string]string{"code": lang}}
		if len(params) > 0 {
			tpl["components"] = []map[string]any{{"type": "body", "parameters": params}}
		}
		body["type"], body["template"] = "template", tpl
	} else {
		body["type"], body["text"] = "text", map[string]any{"body": msg.Body, "preview_url": false}
	}
	url := fmt.Sprintf("%s/%s/%s/messages", s.cfg.BaseURL, s.cfg.APIVersion, s.cfg.PhoneNumberID)
	req, err := providerhttp.NewJSONRequest(ctx, url, body)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Authorization", "Bearer "+s.cfg.Token)
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	if !res.OK() {
		var e struct {
			Error struct {
				Message string `json:"message"`
				Code    int    `json:"code"`
			} `json:"error"`
		}
		_ = json.Unmarshal(res.Body, &e)
		code := strconv.Itoa(e.Error.Code)
		if retryableCodes[e.Error.Code] || domain.HTTPRetryable(res.Status) {
			return domain.Receipt{}, domain.Retry(Name, res.Status, code).Wrap(errors.New(e.Error.Message))
		}
		return domain.Receipt{}, domain.Permanent(Name, res.Status, code).Wrap(errors.New(e.Error.Message))
	}
	var ok struct {
		Messages []struct {
			ID string `json:"id"`
		} `json:"messages"`
	}
	_ = json.Unmarshal(res.Body, &ok)
	id := ""
	if len(ok.Messages) > 0 {
		id = ok.Messages[0].ID
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: id, AcceptedAt: time.Now().UTC()}, nil
}
