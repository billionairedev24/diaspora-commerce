package domain

import (
	"errors"
	"strings"
	"time"
)

// Template is one version of a message template for a key, locale and channel. Email bodies are Go
// html/template (compiled React Email / MJML HTML can be stored as-is with {{.field}} placeholders); subjects,
// text bodies, SMS, push and WhatsApp bodies are text/template.
type Template struct {
	Key       string    `json:"key"`
	Locale    string    `json:"locale"`
	Channel   Channel   `json:"channel"`
	Version   int       `json:"version"`
	Category  Category  `json:"category"`
	Subject   string    `json:"subject,omitempty"` // email subject, push title, WhatsApp template name
	BodyHTML  string    `json:"bodyHtml,omitempty"`
	BodyText  string    `json:"bodyText"`
	CreatedBy string    `json:"createdBy,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
}

// ErrTemplateNotFound is returned when no template matches.
var ErrTemplateNotFound = errors.New("template not found")

// Validate checks a template before it is stored.
func (t Template) Validate() error {
	var errs []error
	if t.Key == "" || t.Locale == "" {
		errs = append(errs, errors.New("key and locale are required"))
	}
	if !t.Channel.Valid() {
		errs = append(errs, errors.New("unknown channel"))
	}
	if !t.Category.Valid() {
		errs = append(errs, errors.New("unknown category"))
	}
	if strings.TrimSpace(t.BodyText) == "" && strings.TrimSpace(t.BodyHTML) == "" {
		errs = append(errs, errors.New("a body is required"))
	}
	if t.Channel == ChannelEmail && strings.TrimSpace(t.Subject) == "" {
		errs = append(errs, errors.New("email templates need a subject"))
	}
	if len(errs) > 0 {
		return errors.Join(append([]error{ErrInvalidRequest}, errs...)...)
	}
	return nil
}

// LocaleFallbacks returns the lookup order for a locale: "fr-CA" → ["fr-CA", "fr", "en"].
func LocaleFallbacks(locale string) []string {
	out := []string{}
	add := func(l string) {
		for _, x := range out {
			if x == l {
				return
			}
		}
		out = append(out, l)
	}
	if locale != "" {
		add(locale)
		if lang, _, ok := strings.Cut(locale, "-"); ok {
			add(lang)
		}
	}
	add("en")
	return out
}

// Rendered is a template rendered with data.
type Rendered struct {
	Subject  string   `json:"subject,omitempty"`
	HTML     string   `json:"html,omitempty"`
	Text     string   `json:"text"`
	Version  int      `json:"version"`
	Category Category `json:"category"`
}

// Suppression is an address that must not receive non-security messages.
type Suppression struct {
	Address   string    `json:"address"`
	Reason    string    `json:"reason"` // bounce, complaint, unsubscribe, manual
	CreatedAt time.Time `json:"createdAt"`
}

// ListFilter filters the notification log.
type ListFilter struct {
	Recipient string // recipient id or address
	Channel   Channel
	Status    Status
	Before    time.Time
	Limit     int
}
