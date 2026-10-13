// Package localtest is a stand-in carrier for local development without carrier credentials: it rates, buys
// labels (a printable PDF marked TEST), voids, tracks and schedules pickups entirely in memory, under the real
// carrier's code (usps, ups …) so sellers, fulfilment and the storefront run the whole shipping flow unchanged.
// Tracking advances on the clock from the label's creation time (encoded in the tracking number): picked up after
// 2 minutes, in transit after 5, out for delivery after 10, delivered after 15. Only wired when
// localTestCarriers is on (config/local.yaml) and the carrier has no credentials; never in dev, uat or prod.
package localtest

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

type service struct {
	code     domain.ServiceCode
	name     string
	days     int
	baseCent int64
	perLb    int64
}

var services = map[domain.CarrierCode][]service{
	domain.USPS:  {{"USPS_GROUND_ADVANTAGE", "USPS Ground Advantage (test)", 4, 545, 85}, {"PRIORITY_MAIL", "Priority Mail (test)", 2, 895, 120}, {"PRIORITY_MAIL_EXPRESS", "Priority Mail Express (test)", 1, 2895, 210}},
	domain.UPS:   {{"03", "UPS Ground (test)", 4, 1095, 110}, {"02", "UPS 2nd Day Air (test)", 2, 2195, 180}, {"01", "UPS Next Day Air (test)", 1, 3895, 260}},
	domain.FedEx: {{"FEDEX_GROUND", "FedEx Ground (test)", 4, 1045, 105}, {"FEDEX_2_DAY", "FedEx 2Day (test)", 2, 2095, 175}, {"PRIORITY_OVERNIGHT", "FedEx Priority Overnight (test)", 1, 4095, 270}},
	domain.DHL:   {{"P", "DHL Express Worldwide (test)", 3, 3495, 300}},
}

type carrierImpl struct {
	code domain.CarrierCode
	now  func() time.Time
}

var _ port.Carrier = (*carrierImpl)(nil)

// New returns the test stand-in for code.
func New(code domain.CarrierCode, now func() time.Time) port.Carrier {
	if now == nil {
		now = time.Now
	}
	return &carrierImpl{code: code, now: now}
}

func (c *carrierImpl) Code() domain.CarrierCode { return c.code }

func (c *carrierImpl) Rate(_ context.Context, s domain.Shipment) ([]domain.Quote, error) {
	if err := s.Validate(); err != nil {
		return nil, err
	}
	w := math.Max(1, math.Ceil(s.TotalWeightLb()))
	ship := s.ShipDate
	if ship.IsZero() {
		ship = c.now()
	}
	out := make([]domain.Quote, 0, len(services[c.code]))
	for _, sv := range services[c.code] {
		eta := ship.AddDate(0, 0, sv.days)
		out = append(out, domain.Quote{Carrier: c.code, Service: sv.code, ServiceName: sv.name,
			AmountMinor: sv.baseCent + int64(w)*sv.perLb, Currency: "USD", TransitDays: sv.days, EstimatedDelivery: &eta})
	}
	return out, nil
}

func (c *carrierImpl) BuyLabel(ctx context.Context, s domain.Shipment, svc domain.ServiceCode) (domain.Label, error) {
	quotes, err := c.Rate(ctx, s)
	if err != nil {
		return domain.Label{}, err
	}
	var q *domain.Quote
	for i := range quotes {
		if quotes[i].Service == svc {
			q = &quotes[i]
		}
	}
	if q == nil {
		return domain.Label{}, fmt.Errorf("%w: service %q is not offered by %s (test)", domain.ErrInvalid, svc, c.code)
	}
	now := c.now().UTC()
	tracking := trackingNumber(c.code, now)
	return domain.Label{Carrier: c.code, Service: svc, TrackingNumber: tracking, CarrierShipmentID: "TEST-" + tracking,
		CostMinor: q.AmountMinor, Currency: "USD", Format: domain.FormatPDF, Data: labelPDF(c.code, q.ServiceName, tracking, s)}, nil
}

func (c *carrierImpl) Void(_ context.Context, trackingNumber string) error {
	if _, ok := createdAt(trackingNumber); !ok {
		return fmt.Errorf("%w: not a test tracking number", domain.ErrInvalid)
	}
	return nil
}

// steps is the scripted journey after the label is created.
var steps = []struct {
	after time.Duration
	state domain.ShipmentState
	code  string
	desc  string
	where string
}{
	{0, domain.StateLabelCreated, "LC", "Shipping label created (test carrier)", ""},
	{2 * time.Minute, domain.StatePickedUp, "PU", "Picked up", "Origin facility"},
	{5 * time.Minute, domain.StateInTransit, "IT", "In transit to destination", "Regional hub"},
	{10 * time.Minute, domain.StateOutForDelivery, "OD", "Out for delivery", "Destination post office"},
	{15 * time.Minute, domain.StateDelivered, "DL", "Delivered, front door", "Destination"},
}

func (c *carrierImpl) Track(_ context.Context, trackingNumber string) ([]domain.TrackingEvent, error) {
	at, ok := createdAt(trackingNumber)
	if !ok {
		return nil, fmt.Errorf("%w: unknown test tracking number %s", domain.ErrNotFound, trackingNumber)
	}
	now := c.now()
	out := []domain.TrackingEvent{}
	for i, st := range steps {
		when := at.Add(st.after)
		if when.After(now) {
			break
		}
		out = append(out, domain.TrackingEvent{Carrier: c.code, TrackingNumber: trackingNumber,
			EventID: domain.EventKey(trackingNumber, st.code), Code: st.code, Description: st.desc, State: st.state,
			OccurredAt: when.UTC(), Location: st.where, Sequence: i + 1})
	}
	return out, nil
}

func (c *carrierImpl) SchedulePickup(_ context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	if err := p.Validate(); err != nil {
		return domain.Pickup{}, err
	}
	now := c.now().UTC()
	id := "TESTPU" + strconv.FormatInt(now.UnixNano()%1_000_000_000, 10)
	return domain.Pickup{ID: id, Carrier: c.code, Confirmation: id, Date: p.Date, OriginNode: p.OriginNode, CreatedAt: now}, nil
}

func (c *carrierImpl) ValidateAddress(_ context.Context, a domain.Address) (domain.AddressValidation, error) {
	valid := strings.TrimSpace(a.Line1) != "" && strings.TrimSpace(a.City) != "" && len(strings.TrimSpace(a.PostalCode)) >= 5
	msgs := []string{"Checked by the local test carrier (format only)"}
	return domain.AddressValidation{Carrier: c.code, Valid: valid, Messages: msgs}, nil
}

// trackingNumber is TEST + carrier + the creation time in milliseconds, so Track can replay the journey statelessly.
func trackingNumber(code domain.CarrierCode, at time.Time) string {
	return "TEST" + strings.ToUpper(string(code)) + strconv.FormatInt(at.UnixMilli(), 10)
}

func createdAt(tracking string) (time.Time, bool) {
	t := strings.ToUpper(strings.TrimSpace(tracking))
	if !strings.HasPrefix(t, "TEST") {
		return time.Time{}, false
	}
	digits := strings.TrimLeft(t[4:], "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
	ms, err := strconv.ParseInt(digits, 10, 64)
	if err != nil || ms <= 0 {
		return time.Time{}, false
	}
	return time.UnixMilli(ms), true
}

// labelPDF draws a 4×6 in label (288×432 pt) with the addresses and tracking number, marked as a test label.
func labelPDF(code domain.CarrierCode, serviceName, tracking string, s domain.Shipment) []byte {
	lines := []struct {
		size int
		text string
	}{
		{18, "TEST LABEL - NOT FOR SHIPPING"},
		{11, strings.ToUpper(string(code)) + " · " + serviceName},
		{9, ""},
		{9, "FROM: " + s.From.Name},
		{9, s.From.Line1},
		{9, strings.TrimSpace(s.From.City + ", " + s.From.State + " " + s.From.PostalCode)},
		{9, ""},
		{12, "SHIP TO: " + s.To.Name},
		{12, s.To.Line1 + " " + s.To.Line2},
		{12, strings.TrimSpace(s.To.City + ", " + s.To.State + " " + s.To.PostalCode)},
		{9, ""},
		{14, "TRACKING # " + tracking},
		{9, "Ref: " + s.Reference},
	}
	var content bytes.Buffer
	y := 400
	for _, l := range lines {
		if l.text != "" {
			fmt.Fprintf(&content, "BT /F1 %d Tf 18 %d Td (%s) Tj ET\n", l.size, y, pdfEscape(l.text))
		}
		y -= l.size + 8
	}
	// Barcode-like bars under the tracking number.
	for i, r := range tracking {
		w := 1 + int(r)%3
		fmt.Fprintf(&content, "%d 40 %d 50 re f\n", 18+i*9, w)
	}
	objs := []string{
		"<< /Type /Catalog /Pages 2 0 R >>",
		"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
		"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 288 432] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
		fmt.Sprintf("<< /Length %d >>\nstream\n%sendstream", content.Len(), content.String()),
		"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
	}
	var b bytes.Buffer
	b.WriteString("%PDF-1.4\n")
	offsets := make([]int, len(objs))
	for i, o := range objs {
		offsets[i] = b.Len()
		fmt.Fprintf(&b, "%d 0 obj\n%s\nendobj\n", i+1, o)
	}
	xref := b.Len()
	fmt.Fprintf(&b, "xref\n0 %d\n0000000000 65535 f \n", len(objs)+1)
	for _, off := range offsets {
		fmt.Fprintf(&b, "%010d 00000 n \n", off)
	}
	fmt.Fprintf(&b, "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(objs)+1, xref)
	return b.Bytes()
}

func pdfEscape(s string) string {
	s = strings.NewReplacer(`\`, `\\`, "(", `\(`, ")", `\)`, "·", "-").Replace(s)
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r < 128 {
			out = append(out, r)
		}
	}
	return string(out)
}
