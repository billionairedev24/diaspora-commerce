// Package port declares the outbound interfaces the application depends on.
package port

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// EmailSender delivers email (Resend, SendGrid, Amazon SES, SMTP, log). Errors are *domain.ProviderError
// classified as retryable or permanent.
type EmailSender interface {
	Name() string
	Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error)
}

// SmsSender delivers text messages (Twilio, Vonage, AWS End User Messaging, log).
type SmsSender interface {
	Name() string
	Send(ctx context.Context, msg domain.SmsMessage) (domain.Receipt, error)
}

// PushSender delivers push notifications (FCM, APNs, log).
type PushSender interface {
	Name() string
	// Accepts reports whether this provider can deliver to the token's platform.
	Accepts(platform domain.PushPlatform) bool
	Send(ctx context.Context, msg domain.PushMessage) (domain.Receipt, error)
}

// ChatSender delivers chat messages (WhatsApp via Meta Cloud API, log).
type ChatSender interface {
	Name() string
	Send(ctx context.Context, msg domain.ChatMessage) (domain.Receipt, error)
}

// ConsentQuery asks whether a message may be sent to an address now.
type ConsentQuery struct {
	RecipientID string
	Address     string
	Channel     domain.Channel
	Category    domain.Category
	TimeZone    string
	Market      string
}

// ConsentChecker applies consent, suppression, preference and quiet-hour rules (domain.Decide) to stored facts.
type ConsentChecker interface {
	Check(ctx context.Context, q ConsentQuery) (domain.Decision, error)
}

// ConsentStore records TCPA SMS consent, preferences and the suppression list.
type ConsentStore interface {
	RecordSmsConsent(ctx context.Context, c domain.SmsConsent) error
	// OptOutSms revokes SMS consent for a phone (STOP keywords).
	OptOutSms(ctx context.Context, phone, via string, at time.Time) error
	// OptInSms restores SMS consent for a phone (START keyword), creating a record if needed.
	OptInSms(ctx context.Context, phone, source, textVersion string, at time.Time) error
	SmsConsent(ctx context.Context, phone string) (*domain.SmsConsent, error)
	Preferences(ctx context.Context, recipientKey string) ([]domain.Preference, error)
	SetPreferences(ctx context.Context, recipientKey string, prefs []domain.Preference) error
	Suppress(ctx context.Context, address, reason string, at time.Time) error
	Unsuppress(ctx context.Context, address string) error
	Suppressions(ctx context.Context, address string, limit int) ([]domain.Suppression, error)
}

// TemplateStore holds versioned templates.
type TemplateStore interface {
	// Find returns a version (0 = latest) for key/channel, trying locales in order.
	Find(ctx context.Context, key string, locales []string, channel domain.Channel, version int) (domain.Template, error)
	// Create stores t as the next version for its key/locale/channel.
	Create(ctx context.Context, t domain.Template) (domain.Template, error)
	// List returns templates (all versions) for key, or the latest of every key when key is empty.
	List(ctx context.Context, key string) ([]domain.Template, error)
}

// NotificationStore is the outbox-style notification table with its inbox and dead-letter tables.
type NotificationStore interface {
	// Enqueue inserts notifications, ignoring ones whose idempotency key already exists, and returns the stored rows.
	Enqueue(ctx context.Context, ns []domain.Notification) ([]domain.Notification, error)
	// EnqueueForEvent records eventID in the consumer's inbox and enqueues ns in one transaction. It returns false
	// (and enqueues nothing) when the event was already processed.
	EnqueueForEvent(ctx context.Context, consumer, eventID string, ns []domain.Notification) (bool, error)
	// Claim locks up to limit due notifications with SELECT … FOR UPDATE SKIP LOCKED and leases them for lease.
	Claim(ctx context.Context, limit int, lease time.Duration) ([]domain.Notification, error)
	MarkSent(ctx context.Context, id string, r domain.Receipt) error
	// Reschedule sets the next attempt without counting it as a failure (quiet hours).
	Reschedule(ctx context.Context, id string, at time.Time, reason string) error
	// MarkRetry records a failed attempt and when to try again.
	MarkRetry(ctx context.Context, id string, attempts int, next time.Time, lastError string) error
	// MarkFinal sets a terminal status; deadLetter also records it in the dead-letter table.
	MarkFinal(ctx context.Context, id string, status domain.Status, attempts int, reason string, deadLetter bool) error
	Get(ctx context.Context, id string) (domain.Notification, error)
	List(ctx context.Context, f domain.ListFilter) ([]domain.Notification, error)
	// ApplyDeliveryEvent updates status from a provider webhook (never backwards) and logs the receipt.
	ApplyDeliveryEvent(ctx context.Context, e domain.DeliveryEvent) (bool, error)
	DeadLetters(ctx context.Context, limit int) ([]domain.DeadLetter, error)
	// Replay re-queues a dead-lettered notification.
	Replay(ctx context.Context, deadLetterID string) (domain.Notification, error)
	Ping(ctx context.Context) error
}

// ContactResolver finds the caller's own email and phone (test-send only goes to the editor).
type ContactResolver interface {
	Resolve(ctx context.Context) (email, phone string, err error)
}
