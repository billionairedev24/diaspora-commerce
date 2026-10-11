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

// AttachmentCapable is implemented by email senders that can attach files (Resend, SendGrid, SES, SMTP). Senders
// without it never receive attachments: the router strips them and the email's link is the fallback.
type AttachmentCapable interface {
	SupportsAttachments() bool
}

// AttachmentFetcher downloads an email attachment from its (signed) URL. It enforces the host allow-list and the
// size limit and returns domain.ErrAttachmentRejected for files that are not allowed.
type AttachmentFetcher interface {
	Fetch(ctx context.Context, ref domain.AttachmentRef) (domain.Attachment, error)
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

// Contact is what identity knows about how to reach a user. Empty fields are unknown.
type Contact struct {
	UserID     string             `json:"userId"`
	FirstName  string             `json:"firstName,omitempty"`
	Email      string             `json:"email,omitempty"`
	Phone      string             `json:"phone,omitempty"`
	PushTokens []domain.PushToken `json:"pushTokens,omitempty"`
	Locale     string             `json:"locale,omitempty"`
	TimeZone   string             `json:"timeZone,omitempty"`
	Market     string             `json:"market,omitempty"`
	// Disabled lists channels the user switched off in their account settings. Security messages ignore it;
	// the per-category preferences stored in notifications still apply at dispatch.
	Disabled []domain.Channel `json:"disabled,omitempty"`
}

// RecipientResolver looks up contact details by identity user id, and the owners of a seller/provider
// organisation, for events whose payloads carry ids rather than addresses. Unknown users and organisations return
// domain.ErrRecipientNotFound; any other error is transient (the event is redelivered).
type RecipientResolver interface {
	Contact(ctx context.Context, userID string) (Contact, error)
	OrgOwners(ctx context.Context, orgID string) ([]string, error)
}

// AudienceMember is one opted-in recipient of a pre-order window's comms on one channel: a signed-in shopper
// (UserID; addresses resolved through identity) or an email-only subscriber (Email). UnsubscribeURL withdraws the
// consent the member gave delivery-runs.
type AudienceMember struct {
	SubscriptionID string `json:"subscriptionId"`
	UserID         string `json:"userId"`
	Email          string `json:"email"`
	Phone          string `json:"phone"`
	UnsubscribeURL string `json:"unsubscribeUrl"`
}

// AudiencePage is one keyset page of a window's audience; Next is empty on the last page.
type AudiencePage struct {
	Members []AudienceMember `json:"members"`
	Next    string           `json:"next"`
}

// Pre-order audience segments.
const (
	// AudienceSubscribers: area subscribers (consented channel) and past buyers in the window's targets.
	AudienceSubscribers = "SUBSCRIBERS"
	// AudienceWaitlist: shoppers who joined the waitlist of one of the window's areas.
	AudienceWaitlist = "WAITLIST"
)

// PreorderAudience pages through who may receive a pre-order window's comms on a channel (delivery-runs
// GET /v1/service/preorders/{windowId}/audience?channel=&segment=&after=&limit=, scope delivery-runs:audience.read).
// An unknown window returns domain.ErrRecipientNotFound; other errors are transient.
type PreorderAudience interface {
	Audience(ctx context.Context, windowID string, channel domain.Channel, segment, after string, limit int) (AudiencePage, error)
}

// ContactInvalidator drops a user's cached contact details when identity reports that they changed.
type ContactInvalidator interface {
	Forget(userID string)
}

// StockAlertStore persists back-in-stock subscriptions.
type StockAlertStore interface {
	// Subscribe stores an active alert for (user, atin). It returns created=false when an active alert already
	// exists (left unchanged); a previously notified alert is re-armed and counts as created.
	Subscribe(ctx context.Context, a domain.StockAlert) (domain.StockAlert, bool, error)
	// Unsubscribe deletes the user's alert for atin (domain.ErrNotFound when there is none).
	Unsubscribe(ctx context.Context, userID, atin string) error
	// ListByUser returns the user's alerts, newest first.
	ListByUser(ctx context.Context, userID string) ([]domain.StockAlert, error)
	// ClaimForRestock marks up to limit active alerts for atin as notified by eventID and returns them. Alerts
	// already claimed by the same eventID are returned again, so a redelivered event can finish its work.
	ClaimForRestock(ctx context.Context, atin, eventID string, limit int) ([]domain.StockAlert, error)
}

// BrandSource serves the brand snapshot every email layout renders: footer socials, WhatsApp community link, app
// links, phone, address and legal links (the content service's site config over the configured defaults). Brand
// never blocks: it returns the last good snapshot.
type BrandSource interface {
	Brand() domain.Brand
}

// ProductCatalog finds products to promote (catalog search): related to a seed title, in stock, with a photo.
type ProductCatalog interface {
	Related(ctx context.Context, seed, market string, limit int) ([]domain.PromoProduct, error)
}

// PreorderCatalog lists the published pre-order windows (delivery-runs GET /v1/public/preorders/open).
type PreorderCatalog interface {
	OpenPreorders(ctx context.Context) ([]domain.OpenPreorder, error)
}

// CampaignCatalog lists live house campaigns for a market (content GET /v1/placements/resolve).
type CampaignCatalog interface {
	Campaigns(ctx context.Context, market string) ([]domain.Campaign, error)
}
