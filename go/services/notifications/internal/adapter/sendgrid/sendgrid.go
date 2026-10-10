// Package sendgrid implements port.EmailSender with the SendGrid v3 mail/send API.
package sendgrid

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "sendgrid"

// DefaultBaseURL is the SendGrid API.
const DefaultBaseURL = "https://api.sendgrid.com"

// Config configures the adapter. APIKey comes from SENDGRID_API_KEY.
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

// New returns the SendGrid email sender.
func New(cfg Config, client *http.Client) port.EmailSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, client: client}
}

func (s *senderImpl) Name() string { return Name }

// SupportsAttachments is true: SendGrid takes base64 attachments (30 MB per message).
func (s *senderImpl) SupportsAttachments() bool { return true }

type address struct {
	Email string `json:"email"`
	Name  string `json:"name,omitempty"`
}

type personalization struct {
	To         []address         `json:"to"`
	CustomArgs map[string]string `json:"custom_args,omitempty"`
}

type content struct {
	Type  string `json:"type"`
	Value string `json:"value"`
}

type attachment struct {
	Content     string `json:"content"` // base64
	Type        string `json:"type,omitempty"`
	Filename    string `json:"filename"`
	Disposition string `json:"disposition"`
}

type request struct {
	Attachments      []attachment      `json:"attachments,omitempty"`
	Personalizations []personalization `json:"personalizations"`
	From             address           `json:"from"`
	ReplyTo          *address          `json:"reply_to,omitempty"`
	Subject          string            `json:"subject"`
	Content          []content         `json:"content"`
	Headers          map[string]string `json:"headers,omitempty"`
	Categories       []string          `json:"categories,omitempty"`
}

func parseAddress(v string) address {
	if a, err := mail.ParseAddress(v); err == nil {
		return address{Email: a.Address, Name: a.Name}
	}
	return address{Email: v}
}

func (s *senderImpl) Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error) {
	body := request{
		Personalizations: []personalization{{To: []address{{Email: msg.To}},
			CustomArgs: map[string]string{"notification_id": msg.NotificationID}}},
		From:       parseAddress(msg.From),
		Subject:    msg.Subject,
		Headers:    msg.Headers,
		Categories: []string{string(msg.Category)},
	}
	if msg.ReplyTo != "" {
		r := parseAddress(msg.ReplyTo)
		body.ReplyTo = &r
	}
	if msg.Text != "" { // SendGrid requires text/plain before text/html
		body.Content = append(body.Content, content{Type: "text/plain", Value: msg.Text})
	}
	if msg.HTML != "" {
		body.Content = append(body.Content, content{Type: "text/html", Value: msg.HTML})
	}
	for _, a := range msg.Attachments {
		body.Attachments = append(body.Attachments, attachment{Content: base64.StdEncoding.EncodeToString(a.Content),
			Type: a.ContentType, Filename: a.FileName, Disposition: "attachment"})
	}
	req, err := providerhttp.NewJSONRequest(ctx, s.cfg.BaseURL+"/v3/mail/send", body)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	req.Header.Set("Authorization", "Bearer "+s.cfg.APIKey)
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	if !res.OK() {
		var e struct {
			Errors []struct {
				Field   string `json:"field"`
				Message string `json:"message"`
			} `json:"errors"`
		}
		_ = json.Unmarshal(res.Body, &e)
		code := ""
		if len(e.Errors) > 0 {
			code = e.Errors[0].Field
		}
		return domain.Receipt{}, providerhttp.Classify(Name, res, code).Wrap(res.Detail())
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: res.Header.Get("X-Message-Id"), AcceptedAt: time.Now().UTC()}, nil
}
