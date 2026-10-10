// Package domain holds notification types and rules (consent, quiet hours, routing breakers, retry policy).
// It has no infrastructure dependencies.
package domain

import (
	"errors"
	"strings"
	"time"
)

// Channel is a delivery channel.
type Channel string

// Supported channels.
const (
	ChannelEmail Channel = "email"
	ChannelSMS   Channel = "sms"
	ChannelPush  Channel = "push"
	ChannelChat  Channel = "chat" // WhatsApp
)

// Valid reports whether c is a known channel.
func (c Channel) Valid() bool {
	switch c {
	case ChannelEmail, ChannelSMS, ChannelPush, ChannelChat:
		return true
	default:
		return false
	}
}

// Category classifies a message for consent and quiet-hour rules. Categories come from the template, never from
// the caller, so a caller cannot dress up marketing as a security message.
type Category string

// Categories.
const (
	// CategorySecurity covers OTPs, verification codes and security alerts: always sent.
	CategorySecurity Category = "security"
	// CategoryTransactional covers order, shipment and booking updates: sent unless the channel is opted out.
	CategoryTransactional Category = "transactional"
	// CategoryReminder covers account and compliance reminders: respects preferences and quiet hours.
	CategoryReminder Category = "reminder"
	// CategoryMarketing needs explicit opt-in, respects quiet hours and carries one-click unsubscribe.
	CategoryMarketing Category = "marketing"
)

// Valid reports whether c is a known category.
func (c Category) Valid() bool {
	switch c {
	case CategorySecurity, CategoryTransactional, CategoryReminder, CategoryMarketing:
		return true
	default:
		return false
	}
}

// Status is a notification's delivery status.
type Status string

// Statuses. queued → sent → delivered, or a terminal bounced/failed/complained/suppressed.
const (
	StatusQueued     Status = "queued"
	StatusSent       Status = "sent"
	StatusDelivered  Status = "delivered"
	StatusBounced    Status = "bounced"
	StatusFailed     Status = "failed"
	StatusComplained Status = "complained"
	StatusSuppressed Status = "suppressed"
)

// Rank orders statuses so late or out-of-order provider webhooks never move a message backwards.
func (s Status) Rank() int {
	switch s {
	case StatusQueued:
		return 0
	case StatusSent:
		return 1
	case StatusDelivered:
		return 2
	case StatusBounced, StatusFailed, StatusSuppressed:
		return 3
	case StatusComplained:
		return 4
	default:
		return -1
	}
}

// PushPlatform identifies the push token type.
type PushPlatform string

// Push platforms.
const (
	PushFCM  PushPlatform = "fcm"
	PushAPNs PushPlatform = "apns"
)

// PushToken is a device registration.
type PushToken struct {
	Token    string       `json:"token"`
	Platform PushPlatform `json:"platform"`
}

// Address renders a push token as a stored address ("fcm:<token>").
func (p PushToken) Address() string { return string(p.Platform) + ":" + p.Token }

// ParsePushAddress is the inverse of PushToken.Address.
func ParsePushAddress(addr string) (PushToken, error) {
	plat, tok, ok := strings.Cut(addr, ":")
	if !ok || tok == "" || (plat != string(PushFCM) && plat != string(PushAPNs)) {
		return PushToken{}, errors.New("invalid push address")
	}
	return PushToken{Token: tok, Platform: PushPlatform(plat)}, nil
}

// Recipient identifies who a notification is for, with every address the caller knows.
type Recipient struct {
	ID         string      `json:"id,omitempty"`
	Email      string      `json:"email,omitempty"`
	Phone      string      `json:"phone,omitempty"` // E.164
	PushTokens []PushToken `json:"pushTokens,omitempty"`
	TimeZone   string      `json:"timeZone,omitempty"` // IANA, e.g. America/Chicago
	Locale     string      `json:"locale,omitempty"`
}

// Key returns the preference key: the recipient id when known, else the address.
func Key(recipientID, address string) string {
	if recipientID != "" {
		return recipientID
	}
	return strings.ToLower(address)
}

// Request asks for a templated notification on one or more channels.
type Request struct {
	TemplateKey string         `json:"templateKey"`
	Locale      string         `json:"locale,omitempty"`
	Market      string         `json:"market,omitempty"`
	Channels    []Channel      `json:"channels"`
	Recipient   Recipient      `json:"recipient"`
	Data        map[string]any `json:"data,omitempty"`
	// Attachments are files to attach to the email (signed media links). When empty, data.attachments is used.
	Attachments    []AttachmentRef `json:"attachments,omitempty"`
	IdempotencyKey string          `json:"-"`
	EventID        string          `json:"-"`
}

// ErrInvalidRequest is returned for malformed requests.
var ErrInvalidRequest = errors.New("invalid request")

// Validate checks a request and fills defaults.
func (r Request) Validate() (Request, error) {
	if r.TemplateKey == "" {
		return r, errors.Join(ErrInvalidRequest, errors.New("templateKey is required"))
	}
	if len(r.Channels) == 0 {
		return r, errors.Join(ErrInvalidRequest, errors.New("at least one channel is required"))
	}
	for _, c := range r.Channels {
		if !c.Valid() {
			return r, errors.Join(ErrInvalidRequest, errors.New("unknown channel "+string(c)))
		}
	}
	if r.Locale == "" {
		r.Locale = r.Recipient.Locale
	}
	if r.Locale == "" {
		r.Locale = "en"
	}
	if r.Market == "" {
		r.Market = "us"
	}
	if r.Data == nil {
		r.Data = map[string]any{}
	}
	return r, nil
}

// Notification is one message on one channel to one address (a row of the outbox-style notification table).
type Notification struct {
	ID              string            `json:"id"`
	IdempotencyKey  string            `json:"idempotencyKey"`
	EventID         string            `json:"eventId,omitempty"`
	TemplateKey     string            `json:"templateKey"`
	TemplateVersion int               `json:"templateVersion"`
	Locale          string            `json:"locale"`
	Channel         Channel           `json:"channel"`
	Category        Category          `json:"category"`
	Market          string            `json:"market"`
	RecipientID     string            `json:"recipientId,omitempty"`
	Address         string            `json:"address"`
	TimeZone        string            `json:"timeZone,omitempty"`
	Subject         string            `json:"subject,omitempty"`
	BodyHTML        string            `json:"-"`
	BodyText        string            `json:"bodyText,omitempty"`
	Headers         map[string]string `json:"headers,omitempty"`
	// Attachments (email only) are fetched by the dispatcher when the message is sent.
	Attachments       []AttachmentRef `json:"attachments,omitempty"`
	Status            Status          `json:"status"`
	StatusDetail      string          `json:"statusDetail,omitempty"`
	Attempts          int             `json:"attempts"`
	NextAttemptAt     time.Time       `json:"nextAttemptAt"`
	LastError         string          `json:"lastError,omitempty"`
	Provider          string          `json:"provider,omitempty"`
	ProviderMessageID string          `json:"providerMessageId,omitempty"`
	CreatedAt         time.Time       `json:"createdAt"`
	UpdatedAt         time.Time       `json:"updatedAt"`
	SentAt            *time.Time      `json:"sentAt,omitempty"`
}

// DeadLetter is a notification that exhausted its retries or failed permanently.
type DeadLetter struct {
	ID             string     `json:"id"`
	NotificationID string     `json:"notificationId"`
	Reason         string     `json:"reason"`
	CreatedAt      time.Time  `json:"createdAt"`
	ReplayedAt     *time.Time `json:"replayedAt,omitempty"`
}

// Receipt is a provider's acceptance of a message.
type Receipt struct {
	Provider          string    `json:"provider"`
	ProviderMessageID string    `json:"providerMessageId"`
	AcceptedAt        time.Time `json:"acceptedAt"`
}

// DeliveryEvent is a status update from a provider webhook.
type DeliveryEvent struct {
	Provider          string
	ProviderMessageID string
	NotificationID    string // when the provider echoes our id (SendGrid custom_args)
	Status            Status
	Detail            string
	Address           string // recipient address, used for suppression on bounce/complaint
	OccurredAt        time.Time
}

// Suppresses reports whether the event should add the address to the suppression list.
func (e DeliveryEvent) Suppresses() bool {
	return e.Status == StatusBounced || e.Status == StatusComplained
}

// ErrNotFound is returned when a notification or dead letter does not exist.
var ErrNotFound = errors.New("not found")
