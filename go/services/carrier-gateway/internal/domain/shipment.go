// Package domain holds carrier-gateway types and rules: shipments, quotes, labels, tracking states and the
// error taxonomy every carrier adapter maps its failures to. No infrastructure imports.
package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"
)

// CarrierCode identifies a directly integrated carrier.
type CarrierCode string

// Carriers integrated directly (no aggregator).
const (
	USPS  CarrierCode = "usps"
	UPS   CarrierCode = "ups"
	FedEx CarrierCode = "fedex"
	DHL   CarrierCode = "dhl"
)

// AllCarriers lists every supported carrier in a stable order.
var AllCarriers = []CarrierCode{USPS, UPS, FedEx, DHL}

// ParseCarrier validates a carrier code (case-insensitive).
func ParseCarrier(s string) (CarrierCode, error) {
	c := CarrierCode(strings.ToLower(strings.TrimSpace(s)))
	switch c {
	case USPS, UPS, FedEx, DHL:
		return c, nil
	default:
		return "", fmt.Errorf("%w: unknown carrier %q", ErrInvalid, s)
	}
}

// Address is a postal address with contact details (carriers require a name and phone on labels).
type Address struct {
	Name        string `json:"name,omitempty"`
	Company     string `json:"company,omitempty"`
	Phone       string `json:"phone,omitempty"`
	Email       string `json:"email,omitempty"`
	Line1       string `json:"line1"`
	Line2       string `json:"line2,omitempty"`
	City        string `json:"city"`
	State       string `json:"state,omitempty"`
	PostalCode  string `json:"postalCode"`
	Country     string `json:"country"`
	Residential bool   `json:"residential,omitempty"`
}

// CountryOrUS returns the ISO country code, defaulting to US.
func (a Address) CountryOrUS() string {
	if a.Country == "" {
		return "US"
	}
	return strings.ToUpper(a.Country)
}

// ZIP5 returns the first five characters of a US ZIP (ZIP+4 is split by carriers that want it separately).
func (a Address) ZIP5() string {
	z := strings.TrimSpace(a.PostalCode)
	if len(z) >= 5 {
		return z[:5]
	}
	return z
}

// ZIP4 returns the +4 extension of a US ZIP, if any.
func (a Address) ZIP4() string {
	z := strings.ReplaceAll(strings.TrimSpace(a.PostalCode), "-", "")
	if len(z) == 9 {
		return z[5:]
	}
	return ""
}

// Parcel is one package; imperial units (pounds, inches) as US carriers price in them.
type Parcel struct {
	WeightLb float64 `json:"weightLb"`
	LengthIn float64 `json:"lengthIn"`
	WidthIn  float64 `json:"widthIn"`
	HeightIn float64 `json:"heightIn"`
}

// CustomsLine is one commercial invoice line (international shipments).
type CustomsLine struct {
	Description    string  `json:"description"`
	Quantity       int     `json:"quantity"`
	UnitValueMinor int64   `json:"unitValueMinor"`
	HSCode         string  `json:"hsCode"`
	OriginCountry  string  `json:"originCountry"`
	WeightLb       float64 `json:"weightLb"`
}

// Customs is the customs declaration for international parcels.
type Customs struct {
	Currency      string        `json:"currency"`
	Incoterm      string        `json:"incoterm,omitempty"` // DAP (default) or DDP
	ExportReason  string        `json:"exportReason,omitempty"`
	InvoiceNumber string        `json:"invoiceNumber,omitempty"`
	Lines         []CustomsLine `json:"lines"`
}

// DeclaredValueMinor sums the invoice lines.
func (c Customs) DeclaredValueMinor() int64 {
	var total int64
	for _, l := range c.Lines {
		total += l.UnitValueMinor * int64(l.Quantity)
	}
	return total
}

// Account is the carrier account a shipment is billed to (per fulfilment centre or seller) with how its
// negotiated rates are applied.
type Account struct {
	Carrier CarrierCode `json:"carrier"`
	Number  string      `json:"number,omitempty"`
	// Negotiated asks the carrier for account (contract) rates instead of list rates.
	Negotiated bool `json:"negotiated"`
	// AdjustmentPct is applied on top of the carrier's price (e.g. -5 for an off-invoice rebate, +10 handling).
	AdjustmentPct float64 `json:"adjustmentPct,omitempty"`
}

// Shipment is what we ask carriers to rate or ship.
type Shipment struct {
	Reference  string    `json:"reference,omitempty"` // order/package id printed on the label
	OriginNode string    `json:"originNode"`          // FC id or seller org id (selects carrier accounts)
	From       Address   `json:"from"`
	To         Address   `json:"to"`
	Parcels    []Parcel  `json:"parcels"`
	ShipDate   time.Time `json:"shipDate"`
	Customs    *Customs  `json:"customs,omitempty"`
	Account    Account   `json:"-"`
}

// International reports whether the shipment crosses a border (customs required).
func (s Shipment) International() bool { return s.From.CountryOrUS() != s.To.CountryOrUS() }

// TotalWeightLb sums parcel weights.
func (s Shipment) TotalWeightLb() float64 {
	var w float64
	for _, p := range s.Parcels {
		w += p.WeightLb
	}
	return w
}

// Validate checks the fields every carrier needs.
func (s Shipment) Validate() error {
	switch {
	case s.From.PostalCode == "" || s.To.PostalCode == "":
		return fmt.Errorf("%w: origin and destination postal codes are required", ErrInvalid)
	case len(s.Parcels) == 0:
		return fmt.Errorf("%w: at least one parcel is required", ErrInvalid)
	}
	for i, p := range s.Parcels {
		if p.WeightLb <= 0 || p.WeightLb > 150 {
			return fmt.Errorf("%w: parcel %d weight must be within (0, 150] lb", ErrInvalid, i)
		}
	}
	if s.International() && (s.Customs == nil || len(s.Customs.Lines) == 0) {
		return fmt.Errorf("%w: international shipments need customs lines", ErrInvalid)
	}
	if s.Customs != nil {
		for i, l := range s.Customs.Lines {
			if l.HSCode == "" || l.Quantity <= 0 || l.Description == "" {
				return fmt.Errorf("%w: customs line %d needs description, quantity and HS code", ErrInvalid, i)
			}
		}
	}
	return nil
}

// Fingerprint is a stable hash of everything that changes a rate, used as the quote cache key.
// cartID scopes quotes to one cart; shipments that are otherwise identical share a fingerprint per cart.
func (s Shipment) Fingerprint(cartID string) string {
	var b strings.Builder
	addr := func(a Address) {
		fmt.Fprintf(&b, "|%s|%s|%s|%s|%t", a.CountryOrUS(), strings.ToUpper(a.State), strings.ToUpper(a.PostalCode),
			strings.ToUpper(strings.TrimSpace(a.City)), a.Residential)
	}
	b.WriteString(cartID + "|" + s.OriginNode)
	addr(s.From)
	addr(s.To)
	parcels := make([]string, 0, len(s.Parcels))
	for _, p := range s.Parcels {
		parcels = append(parcels, fmt.Sprintf("%.2f/%.1fx%.1fx%.1f", p.WeightLb, p.LengthIn, p.WidthIn, p.HeightIn))
	}
	sort.Strings(parcels)
	b.WriteString("|" + strings.Join(parcels, ","))
	b.WriteString("|" + s.ShipDate.Format(time.DateOnly))
	if s.Customs != nil {
		fmt.Fprintf(&b, "|%s%d", s.Customs.Currency, s.Customs.DeclaredValueMinor())
	}
	sum := sha256.Sum256([]byte(b.String()))
	return hex.EncodeToString(sum[:16])
}

// ServiceCode is a carrier's own service identifier (USPS mail class, UPS service code, FedEx service type,
// DHL product code).
type ServiceCode string

// Quote is one rated service.
type Quote struct {
	Carrier           CarrierCode `json:"carrier"`
	Service           ServiceCode `json:"service"`
	ServiceName       string      `json:"serviceName"`
	AmountMinor       int64       `json:"amountMinor"`
	ListAmountMinor   int64       `json:"listAmountMinor,omitempty"`
	Currency          string      `json:"currency"`
	Negotiated        bool        `json:"negotiated"`
	TransitDays       int         `json:"transitDays,omitempty"`
	EstimatedDelivery *time.Time  `json:"estimatedDelivery,omitempty"`
}

// Adjust applies an account's percentage adjustment, rounding to the nearest minor unit.
func (q Quote) Adjust(pct float64) Quote {
	if pct == 0 {
		return q
	}
	q.AmountMinor = RoundMinor(float64(q.AmountMinor) * (1 + pct/100))
	return q
}

// LabelFormat is the label image format.
type LabelFormat string

// Label formats.
const (
	FormatPDF LabelFormat = "PDF"
	FormatZPL LabelFormat = "ZPL"
)

// ContentType returns the MIME type for a label format.
func (f LabelFormat) ContentType() string {
	if f == FormatZPL {
		return "application/x-zpl"
	}
	return "application/pdf"
}

// Label is a purchased shipping label as returned by a carrier.
type Label struct {
	Carrier           CarrierCode `json:"carrier"`
	Service           ServiceCode `json:"service"`
	TrackingNumber    string      `json:"trackingNumber"`
	CarrierShipmentID string      `json:"carrierShipmentId,omitempty"`
	CostMinor         int64       `json:"costMinor"`
	Currency          string      `json:"currency"`
	Format            LabelFormat `json:"format"`
	Data              []byte      `json:"-"`
}

// PickupRequest asks a carrier to collect parcels.
type PickupRequest struct {
	Carrier       CarrierCode `json:"carrier"`
	OriginNode    string      `json:"originNode"`
	Address       Address     `json:"address"`
	Date          time.Time   `json:"date"`
	ReadyTime     string      `json:"readyTime"` // HH:MM local
	CloseTime     string      `json:"closeTime"` // HH:MM local
	Packages      int         `json:"packages"`
	TotalWeightLb float64     `json:"totalWeightLb"`
	Location      string      `json:"location,omitempty"` // e.g. "Front desk"
	Service       ServiceCode `json:"service,omitempty"`
	International bool        `json:"international,omitempty"`
	Account       Account     `json:"-"`
}

// Validate checks a pickup request.
func (p PickupRequest) Validate() error {
	if p.Packages <= 0 || p.TotalWeightLb <= 0 || p.Date.IsZero() || p.Address.PostalCode == "" {
		return fmt.Errorf("%w: pickup needs a date, address, package count and weight", ErrInvalid)
	}
	if !validClock(p.ReadyTime) || !validClock(p.CloseTime) || p.ReadyTime >= p.CloseTime {
		return fmt.Errorf("%w: readyTime and closeTime must be HH:MM with ready before close", ErrInvalid)
	}
	return nil
}

func validClock(s string) bool {
	_, err := time.Parse("15:04", s)
	return err == nil
}

// Pickup is a scheduled carrier pickup.
type Pickup struct {
	ID           string      `json:"id"`
	Carrier      CarrierCode `json:"carrier"`
	Confirmation string      `json:"confirmation"`
	Date         time.Time   `json:"date"`
	OriginNode   string      `json:"originNode"`
	CreatedAt    time.Time   `json:"createdAt"`
}

// AddressValidation is a carrier's verdict on an address.
type AddressValidation struct {
	Carrier      CarrierCode `json:"carrier"`
	Valid        bool        `json:"valid"`
	Residential  *bool       `json:"residential,omitempty"`
	Standardized *Address    `json:"standardized,omitempty"`
	Messages     []string    `json:"messages"`
}
