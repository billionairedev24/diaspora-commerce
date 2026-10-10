// Package port declares carrier-gateway's outbound interfaces: the Carrier port every carrier adapter implements,
// and the stores, cache and object storage the use cases depend on.
package port

import (
	"context"
	"net/http"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// Carrier is one directly integrated carrier. Every adapter owns its authentication, retries with backoff on
// 429/5xx and maps failures to *domain.CarrierError (retryable vs permanent).
type Carrier interface {
	Code() domain.CarrierCode
	Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error)
	BuyLabel(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error)
	Void(ctx context.Context, trackingNumber string) error
	Track(ctx context.Context, trackingNumber string) ([]domain.TrackingEvent, error)
	SchedulePickup(ctx context.Context, p domain.PickupRequest) (domain.Pickup, error)
	ValidateAddress(ctx context.Context, a domain.Address) (domain.AddressValidation, error)
}

// WebhookReceiver is implemented by carriers that push tracking (UPS Track Alert, FedEx, DHL).
type WebhookReceiver interface {
	// VerifyWebhook authenticates a push (HMAC signature or shared credential) over the raw body.
	VerifyWebhook(header http.Header, body []byte) error
	// ParseWebhook normalises the pushed payload into tracking events.
	ParseWebhook(body []byte) ([]domain.TrackingEvent, error)
}

// BoxFreeReturner is implemented by carriers whose integration offers label-free (box-free) QR drop-off for
// returns: the customer shows a QR code at a carrier location, which packs and labels the item. The returned label
// carries the QR payload, the drop-off network and the QR image as Data (FormatPNG). None of the current adapters
// (USPS, UPS, FedEx, DHL Express) implement it, so box-free requests fail with domain.ErrUnsupported per carrier.
type BoxFreeReturner interface {
	BuyBoxFreeReturn(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error)
}

// TrackingSubscriber is implemented by carriers that need an explicit push subscription per tracking number
// (UPS Track Alert).
type TrackingSubscriber interface {
	Subscribe(ctx context.Context, trackingNumbers []string) error
}

// AccountResolver returns the carrier accounts (with negotiated-rate settings) for a fulfilment centre or seller,
// limited to enabled carriers.
type AccountResolver interface {
	Accounts(ctx context.Context, originNode string) ([]domain.Account, error)
}

// QuoteCache caches complete rate-shopping results per shipment fingerprint.
type QuoteCache interface {
	Get(ctx context.Context, key string) ([]domain.Quote, bool, error)
	Put(ctx context.Context, key string, quotes []domain.Quote, ttl time.Duration) error
}

// ObjectStore stores label documents (PDF/ZPL).
type ObjectStore interface {
	Put(ctx context.Context, key, contentType string, data []byte) error
	Get(ctx context.Context, key string) ([]byte, error)
	// SignedURL returns a time-limited download URL, or "" when the store cannot sign (local filesystem).
	SignedURL(ctx context.Context, key string, ttl time.Duration) (string, error)
}

// LabelRepository persists shipment_label rows.
type LabelRepository interface {
	// Reserve inserts a purchasing row for rec.IdempotencyKey. When the key already exists it returns the stored
	// row and false.
	Reserve(ctx context.Context, rec domain.LabelRecord) (domain.LabelRecord, bool, error)
	// Complete stores the purchased label, registers its tracking number as an active tracked shipment in state
	// label_created and writes the carrier.label.purchased outbox row, in one transaction.
	Complete(ctx context.Context, rec domain.LabelRecord, purchased events.Envelope) error
	// Release deletes a purchasing row after a failed purchase so the key can be retried.
	Release(ctx context.Context, id string) error
	Get(ctx context.Context, id string) (domain.LabelRecord, error)
	// MarkVoided sets status voided, stops tracking the label and writes the carrier.label.voided outbox row, in
	// one transaction. Only a purchased label can be voided (domain.ErrConflict otherwise), so the event is
	// written once.
	MarkVoided(ctx context.Context, id string, at time.Time, voided events.Envelope) error
	Ping(ctx context.Context) error
}

// TrackingTx is the unit of work for ingesting one tracking event.
type TrackingTx interface {
	// InsertEvent stores the event; false when (carrier, event id) was already stored.
	InsertEvent(ctx context.Context, ev domain.TrackingEvent) (bool, error)
	// LockShipment loads the tracked shipment FOR UPDATE; false when the tracking number is unknown.
	LockShipment(ctx context.Context, carrier domain.CarrierCode, trackingNumber string) (domain.TrackedShipment, bool, error)
	SaveShipment(ctx context.Context, t domain.TrackedShipment) error
	// Enqueue writes an outbox row in the same transaction.
	Enqueue(ctx context.Context, e events.Envelope) error
}

// TrackingStore persists tracked shipments and their events.
type TrackingStore interface {
	InTx(ctx context.Context, fn func(ctx context.Context, tx TrackingTx) error) error
	// Register starts tracking a number (no-op when it is already tracked).
	Register(ctx context.Context, t domain.TrackedShipment) error
	GetShipment(ctx context.Context, carrier domain.CarrierCode, trackingNumber string) (domain.TrackedShipment, error)
	Events(ctx context.Context, carrier domain.CarrierCode, trackingNumber string) ([]domain.TrackingEvent, error)
	// ClaimDuePolls leases up to limit active shipments of carrier whose next poll is due (SKIP LOCKED, so
	// replicas never poll the same number concurrently) and pushes their next poll to now+lease.
	ClaimDuePolls(ctx context.Context, carrier domain.CarrierCode, now time.Time, lease time.Duration, limit int) ([]PollTarget, error)
	// SchedulePoll records the outcome of a poll.
	SchedulePoll(ctx context.Context, carrier domain.CarrierCode, trackingNumber string, next time.Time, failures int) error
}

// PollTarget is a tracked shipment due for polling.
type PollTarget struct {
	Carrier        domain.CarrierCode
	TrackingNumber string
	Failures       int
	CreatedAt      time.Time
}

// Outbox is the transactional outbox for events.
type Outbox interface {
	Enqueue(ctx context.Context, e events.Envelope) error
	// Dispatch locks up to limit due rows (SKIP LOCKED), publishes each and marks it sent, or schedules a retry
	// with backoff when publishing fails. Returns the number of rows published.
	Dispatch(ctx context.Context, limit int, publish func(ctx context.Context, e events.Envelope) error) (int, error)
}

// PickupRepository stores scheduled carrier pickups and customer pickup locations.
type PickupRepository interface {
	SavePickup(ctx context.Context, p domain.Pickup, req domain.PickupRequest) error
	SaveLocation(ctx context.Context, l domain.PickupLocation) error
	GetLocation(ctx context.Context, id string) (domain.PickupLocation, error)
	// ListLocations filters by owner org and/or postal-code prefix; activeOnly hides disabled ones.
	ListLocations(ctx context.Context, ownerOrg, postalPrefix string, activeOnly bool) ([]domain.PickupLocation, error)
}
