package domain

import (
	"fmt"
	"strings"
	"time"
)

// LabelStatus is the lifecycle of a purchased label.
type LabelStatus string

// Label statuses.
const (
	LabelPurchasing LabelStatus = "purchasing" // idempotency slot taken, carrier call in flight
	LabelPurchased  LabelStatus = "purchased"
	LabelVoided     LabelStatus = "voided"
)

// LabelRecord is a row of shipment_label.
type LabelRecord struct {
	ID                string        `json:"id"`
	IdempotencyKey    string        `json:"-"`
	RequestHash       string        `json:"-"`
	Reference         string        `json:"reference,omitempty"`
	OriginNode        string        `json:"originNode"`
	Carrier           CarrierCode   `json:"carrier"`
	Service           ServiceCode   `json:"service"`
	TrackingNumber    string        `json:"trackingNumber,omitempty"`
	CarrierShipmentID string        `json:"carrierShipmentId,omitempty"`
	CostMinor         int64         `json:"costMinor"`
	Currency          string        `json:"currency"`
	Format            LabelFormat   `json:"format"`
	LabelKey          string        `json:"labelKey,omitempty"`
	Status            LabelStatus   `json:"status"`
	TrackingState     ShipmentState `json:"trackingState,omitempty"`
	CreatedAt         time.Time     `json:"createdAt"`
	VoidedAt          *time.Time    `json:"voidedAt,omitempty"`
	Kind              LabelKind     `json:"kind"`
	RateChoice        RateChoice    `json:"rateChoice,omitempty"`
	BoxFree           bool          `json:"boxFree"`
	QRPayload         string        `json:"qrPayload,omitempty"`
	DropOffNetwork    string        `json:"dropOffNetwork,omitempty"`
}

// CanVoid reports whether the label can still be voided: purchased and not yet accepted by the carrier.
func (l LabelRecord) CanVoid() error {
	switch {
	case l.Status == LabelVoided:
		return fmt.Errorf("%w: label already voided", ErrConflict)
	case l.Status != LabelPurchased:
		return fmt.Errorf("%w: label purchase has not completed", ErrConflict)
	case l.TrackingState != "" && l.TrackingState != StateLabelCreated:
		return fmt.Errorf("%w: the carrier has already accepted this parcel (%s)", ErrConflict, l.TrackingState)
	}
	return nil
}

// ObjectKey is where the label document is stored.
func (l LabelRecord) ObjectKey() string {
	var ext string
	switch l.Format {
	case FormatZPL:
		ext = "zpl"
	case FormatPNG:
		ext = "png"
	case FormatPDF:
		ext = "pdf"
	default:
		ext = "pdf"
	}
	return fmt.Sprintf("labels/%s/%s/%s.%s", l.Carrier, l.CreatedAt.UTC().Format("2006/01/02"), l.ID, ext)
}

// PickupLocationKind distinguishes platform pickup points from seller pickup.
type PickupLocationKind string

// Pickup location kinds.
const (
	PickupCounter PickupLocationKind = "PLATFORM_COUNTER"
	PickupLocker  PickupLocationKind = "LOCKER"
	PickupSeller  PickupLocationKind = "SELLER"
)

// OpeningHours is one weekday's hours ("09:00"–"18:00"); weekdays absent from the list are closed.
type OpeningHours struct {
	Weekday time.Weekday `json:"weekday"`
	Open    string       `json:"open"`
	Close   string       `json:"close"`
}

// PickupLocation is where a customer collects an order: a warehouse counter, a locker or a seller's shop.
type PickupLocation struct {
	ID         string             `json:"id"`
	Kind       PickupLocationKind `json:"kind"`
	OwnerOrg   string             `json:"ownerOrg"`
	Name       string             `json:"name"`
	Address    Address            `json:"address"`
	Timezone   string             `json:"timezone"`
	Hours      []OpeningHours     `json:"hours"`
	ReadyHours int                `json:"readyHours"` // typical hours from order to ready-for-pickup
	Active     bool               `json:"active"`
	UpdatedAt  time.Time          `json:"updatedAt"`
}

// Validate checks a pickup location.
func (p PickupLocation) Validate() error {
	switch p.Kind {
	case PickupCounter, PickupLocker, PickupSeller:
	default:
		return fmt.Errorf("%w: kind must be PLATFORM_COUNTER, LOCKER or SELLER", ErrInvalid)
	}
	if strings.TrimSpace(p.Name) == "" || p.Address.Line1 == "" || p.Address.PostalCode == "" || p.OwnerOrg == "" {
		return fmt.Errorf("%w: name, owner and address are required", ErrInvalid)
	}
	if _, err := time.LoadLocation(p.Timezone); err != nil || p.Timezone == "" {
		return fmt.Errorf("%w: timezone must be an IANA zone", ErrInvalid)
	}
	if len(p.Hours) == 0 {
		return fmt.Errorf("%w: opening hours are required", ErrInvalid)
	}
	for _, h := range p.Hours {
		if h.Weekday < time.Sunday || h.Weekday > time.Saturday || !validClock(h.Open) || !validClock(h.Close) || h.Open >= h.Close {
			return fmt.Errorf("%w: hours need a weekday 0-6 and HH:MM open before close", ErrInvalid)
		}
	}
	return nil
}

// PickupReady is the payload of carrier.pickup.ready (notifications tells the customer).
type PickupReady struct {
	LocationID  string         `json:"locationId"`
	Name        string         `json:"name"`
	Kind        string         `json:"kind"`
	Address     Address        `json:"address"`
	Hours       []OpeningHours `json:"hours"`
	OrderRef    string         `json:"orderRef"`
	CustomerRef string         `json:"customerRef"`
	PickupCode  string         `json:"pickupCode,omitempty"`
	ReadyAt     time.Time      `json:"readyAt"`
}
