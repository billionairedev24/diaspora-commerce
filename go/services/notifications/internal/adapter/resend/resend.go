// Package resend implements port.EmailSender with the Resend HTTP API (primary transactional email provider).
package resend

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "resend"

// DefaultBaseURL is the Resend API.
const DefaultBaseURL = "https://api.resend.com"

// Config configures the adapter. APIKey comes from RESEND_API_KEY.
type Config struct {
	APIKey  string
	BaseURL string
}

type senderImpl struct {
	cfg    Config
	client *http.Client
}

var (
	_ port.EmailSender       = (*senderImpl)(nil)
	_ port.AttachmentCapable = (*senderImpl)(nil)
)

// New returns the Resend email sender.
func New(cfg Config, client *http.Client) port.EmailSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, client: client}
}

func (s *senderImpl) Name() string { return Name }

// SupportsAttachments is true: Resend takes base64 attachments (40 MB per email).
func (s *senderImpl) SupportsAttachments() bool { return true }

type tag struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type attachment struct {
	Filename    string `json:"filename"`
	Content     string `json:"content"` // base64
	ContentType string `json:"content_type,omitempty"`
}

type request struct {
	From        string            `json:"from"`
	To          []string          `json:"to"`
	Subject     string            `json:"subject"`
	HTML        string            `json:"html,omitempty"`
	Text        string            `json:"text,omitempty"`
	ReplyTo     string            `json:"reply_to,omitempty"`
	Headers     map[string]string `json:"headers,omitempty"`
	Tags        []tag             `json:"tags,omitempty"`
	Attachments []attachment      `json:"attachments,omitempty"`
}

func (s *senderImpl) Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error) {
	body := request{From: msg.From, To: []string{msg.To}, Subject: msg.Subject, HTML: msg.HTML, Text: msg.Text,
		ReplyTo: msg.ReplyTo, Headers: msg.Headers,
		Tags: []tag{{Name: "category", Value: string(msg.Category)}, {Name: "notification_id", Value: msg.NotificationID}}}
	for _, a := range msg.Attachments {
		body.Attachments = append(body.Attachments, attachment{Filename: a.FileName,
			Content: base64.StdEncoding.EncodeToString(a.Content), ContentType: a.ContentType})
	}
	req, err := providerhttp.NewJSONRequest(ctx, s.cfg.BaseURL+"/emails", body)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Authorization", "Bearer "+s.cfg.APIKey)
	if msg.IdempotencyKey != "" {
		req.Header.Set("Idempotency-Key", msg.IdempotencyKey)
	}
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	var parsed struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	_ = json.Unmarshal(res.Body, &parsed)
	if !res.OK() {
		// A concurrent request with the same Idempotency-Key is still in flight: try again later.
		if res.Status == http.StatusConflict && parsed.Name == "concurrent_idempotent_requests" {
			return domain.Receipt{}, domain.Retry(Name, res.Status, parsed.Name).Wrap(nil)
		}
		return domain.Receipt{}, providerhttp.Classify(Name, res, parsed.Name).Wrap(res.Detail())
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: parsed.ID, AcceptedAt: time.Now().UTC()}, nil
}
