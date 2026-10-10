package domain

import (
	"fmt"
	"slices"
	"strings"
	"time"
)

// Fulfilment is how the shipment a label is bought for is fulfilled.
type Fulfilment string

// Fulfilment kinds. Only seller-shipped labels can be charged to the seller.
const (
	FulfilmentSellerShipped        Fulfilment = "SELLER_SHIPPED"
	FulfilmentFBA                  Fulfilment = "FBA"
	FulfilmentAfroTransactDelivery Fulfilment = "AFROTRANSACT_DELIVERY"
)

// ParseFulfilment validates a fulfilment (case-insensitive); empty means seller-shipped.
func ParseFulfilment(s string) (Fulfilment, error) {
	f := Fulfilment(strings.ToUpper(strings.TrimSpace(s)))
	switch f {
	case "":
		return FulfilmentSellerShipped, nil
	case FulfilmentSellerShipped, FulfilmentFBA, FulfilmentAfroTransactDelivery:
		return f, nil
	default:
		return "", fmt.Errorf("%w: fulfilment must be SELLER_SHIPPED, FBA or AFROTRANSACT_DELIVERY", ErrInvalid)
	}
}

// BillTo is who pays for a label.
type BillTo string

// Label payers.
const (
	// BillSeller charges the label (carrier cost + handling) to the buying seller organisation in the payments ledger.
	BillSeller BillTo = "SELLER"
	// BillAfroTransact keeps the label as AfroTransact's own cost (FBA, AfroTransact Delivery, Retail, returns, FCs).
	BillAfroTransact BillTo = "AFROTRANSACT"
)

// Why a label is (not) charged to the seller.
const (
	BillingSellerShipped        = "SELLER_SHIPPED"
	BillingRetail               = "RETAIL"
	BillingFBA                  = "FBA"
	BillingAfroTransactDelivery = "AFROTRANSACT_DELIVERY"
	BillingReturn               = "RETURN"
	BillingPlatformCaller       = "PLATFORM_CALLER"
	BillingPlatformOrigin       = "PLATFORM_ORIGIN"
)

// Label events on at.carrier-shipment.events.
const (
	EventLabelPurchased   = "carrier.label.purchased"
	EventLabelVoided      = "carrier.label.voided"
	LabelVoidReasonVoided = "VOIDED"
)

const maxHandlingPct float64 = 100

// LabelPayer is who bought a label: an organisation member acting for OrgID, or a platform caller (staff or a
// service such as fulfilment buying FC labels).
type LabelPayer struct {
	OrgID    string
	Platform bool
}

// BillingPolicy decides who pays for a label and the handling markup added to seller-paid labels.
type BillingPolicy struct {
	// RetailOrgIDs are AfroTransact Retail organisations (merchant of record: never charged for labels).
	RetailOrgIDs []string
	// PlatformNodes are AfroTransact origins (fulfilment centres): labels shipped from them are never seller-paid.
	PlatformNodes []string
	// HandlingPct is a percentage of the carrier cost added to seller-paid labels (0 by default).
	HandlingPct float64
	// HandlingFixedMinor is a fixed amount added to seller-paid labels (0 by default).
	HandlingFixedMinor int64
}

// Validate rejects negative or absurd markups.
func (p BillingPolicy) Validate() error {
	if p.HandlingPct < 0 || p.HandlingPct > maxHandlingPct || p.HandlingFixedMinor < 0 {
		return fmt.Errorf("%w: label handling markup must be 0-100%% and a non-negative fixed amount", ErrInvalid)
	}
	return nil
}

// Decide returns who pays for a label and why. A label is charged to the seller only when an organisation member
// buys an outbound label for a seller-shipped shipment leaving that organisation's own origin, and the organisation
// is not AfroTransact Retail.
func (p BillingPolicy) Decide(payer LabelPayer, origin string, kind LabelKind, f Fulfilment) (BillTo, string) {
	org := strings.TrimSpace(payer.OrgID)
	switch {
	case kind == KindReturn:
		return BillAfroTransact, BillingReturn
	case payer.Platform || org == "":
		return BillAfroTransact, BillingPlatformCaller
	case slices.Contains(p.RetailOrgIDs, org):
		return BillAfroTransact, BillingRetail
	case f == FulfilmentFBA:
		return BillAfroTransact, BillingFBA
	case f == FulfilmentAfroTransactDelivery:
		return BillAfroTransact, BillingAfroTransactDelivery
	case slices.Contains(p.PlatformNodes, origin) || origin != org:
		return BillAfroTransact, BillingPlatformOrigin
	default:
		return BillSeller, BillingSellerShipped
	}
}

// Handling is the markup on a seller-paid label of costMinor (percentage rounded half away from zero, plus the
// fixed amount).
func (p BillingPolicy) Handling(costMinor int64) int64 {
	if costMinor <= 0 && p.HandlingFixedMinor == 0 {
		return 0
	}
	return RoundMinor(float64(costMinor)*p.HandlingPct/100) + p.HandlingFixedMinor
}

// LabelEvent is the payload of carrier.label.purchased and carrier.label.voided. payments-service charges
// AmountMinor (carrier cost + handling) to OrgID when BillTo is SELLER, and credits it back once on a void.
type LabelEvent struct {
	LabelID        string      `json:"labelId"`
	OrgID          string      `json:"orgId,omitempty"`
	ShipmentID     string      `json:"shipmentId,omitempty"`
	OrderID        string      `json:"orderId,omitempty"`
	Reference      string      `json:"reference,omitempty"`
	OriginNode     string      `json:"originNode"`
	Carrier        CarrierCode `json:"carrier"`
	Service        ServiceCode `json:"service"`
	TrackingNumber string      `json:"trackingNumber"`
	Kind           LabelKind   `json:"kind"`
	Fulfilment     Fulfilment  `json:"fulfilment"`
	BillTo         BillTo      `json:"billTo"`
	BillingReason  string      `json:"billingReason"`
	CostMinor      int64       `json:"costMinor"`
	HandlingMinor  int64       `json:"handlingMinor"`
	AmountMinor    int64       `json:"amountMinor"`
	Currency       string      `json:"currency"`
	PurchasedAt    time.Time   `json:"purchasedAt"`
	VoidedAt       *time.Time  `json:"voidedAt,omitempty"`
	VoidReason     string      `json:"voidReason,omitempty"`
}

// Event returns the label event payload of a purchased (or voided) label.
func (l LabelRecord) Event() LabelEvent {
	e := LabelEvent{LabelID: l.ID, OrgID: l.OrgID, ShipmentID: l.ShipmentID, OrderID: l.OrderID, Reference: l.Reference,
		OriginNode: l.OriginNode, Carrier: l.Carrier, Service: l.Service, TrackingNumber: l.TrackingNumber,
		Kind: l.Kind, Fulfilment: l.Fulfilment, BillTo: l.BillTo, BillingReason: l.BillingReason, CostMinor: l.CostMinor,
		HandlingMinor: l.HandlingMinor, AmountMinor: l.CostMinor + l.HandlingMinor, Currency: l.Currency,
		PurchasedAt: l.CreatedAt.UTC(), VoidedAt: l.VoidedAt}
	if l.Status == LabelVoided {
		e.VoidReason = LabelVoidReasonVoided
	}
	return e
}

// EventKey is the Kafka key of a label's events: the same as its tracking events, so a parcel's purchase, scans and
// void stay in order on one partition.
func (l LabelRecord) EventKey() string { return string(l.Carrier) + ":" + l.TrackingNumber }
