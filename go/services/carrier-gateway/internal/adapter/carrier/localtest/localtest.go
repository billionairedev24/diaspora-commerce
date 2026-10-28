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
	"crypto/sha256"
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

// labelPDF draws a professional 4×6 in shipping label (288×432 pt) styled
// authentically for USPS, UPS, FedEx, or DHL with carrier headers, routing blocks,
// barcode symbology, and standard shipping label layout.
func labelPDF(code domain.CarrierCode, serviceName, tracking string, s domain.Shipment) []byte {
	var content bytes.Buffer

	// Helpers for PDF drawing
	line := func(x1, y1, x2, y2 float64, lineWidth float64) {
		fmt.Fprintf(&content, "%.1f w %.1f %.1f m %.1f %.1f l S\n", lineWidth, x1, y1, x2, y2)
	}
	rect := func(x, y, w, h float64, lineWidth float64) {
		fmt.Fprintf(&content, "%.1f w %.1f %.1f %.1f %.1f re S\n", lineWidth, x, y, w, h)
	}
	fillRect := func(x, y, w, h float64) {
		fmt.Fprintf(&content, "%.1f %.1f %.1f %.1f re f\n", x, y, w, h)
	}
	text := func(font string, size float64, x, y float64, str string) {
		if strings.TrimSpace(str) == "" {
			return
		}
		fmt.Fprintf(&content, "BT /%s %.1f Tf %.1f %.1f Td (%s) Tj ET\n", font, size, x, y, pdfEscape(str))
	}

	// Outer border for 4x6 label (margins 8 pt)
	rect(8, 8, 272, 416, 1.0)

	carrierUpper := strings.ToUpper(string(code))
	cleanServiceName := strings.TrimSuffix(serviceName, " (test)")

	// Header section (Y: 375 to 424)
	switch code {
	case domain.USPS:
		// USPS Priority Mail banner
		fillRect(8, 382, 272, 42)
		fmt.Fprintf(&content, "1 1 1 rg 1 1 1 RG\n") // white text
		text("F2", 16, 16, 404, "PRIORITY MAIL")
		text("F1", 8, 16, 392, "U.S. POSTAGE PAID · AFROTRANSACT INC")
		text("F2", 11, 235, 395, "e-VS")
		fmt.Fprintf(&content, "0 0 0 rg 0 0 0 RG\n") // restore black
		line(8, 382, 280, 382, 1.5)
		line(8, 350, 280, 350, 1.0)
		text("F2", 10, 14, 362, cleanServiceName)
		text("F1", 8, 200, 362, "COMMERCIAL BASE")

	case domain.UPS:
		// UPS Ground / Air shield & badge header
		fillRect(14, 384, 42, 34)
		fmt.Fprintf(&content, "1 1 1 rg 1 1 1 RG\n")
		text("F2", 16, 18, 396, "UPS")
		fmt.Fprintf(&content, "0 0 0 rg 0 0 0 RG\n")
		text("F2", 16, 64, 402, "UPS "+cleanServiceName)
		text("F1", 8, 64, 388, "TRACKING #: "+tracking)
		line(8, 380, 280, 380, 1.5)

	case domain.FedEx:
		// FedEx header with big bold branding
		text("F2", 22, 14, 398, "FedEx")
		text("F2", 13, 85, 400, cleanServiceName)
		text("F1", 7.5, 85, 388, "EXPRESS · TRK: "+tracking)
		line(8, 380, 280, 380, 1.5)

	case domain.DHL:
		// DHL Express header
		fillRect(8, 382, 70, 42)
		fmt.Fprintf(&content, "1 1 1 rg 1 1 1 RG\n")
		text("F2", 20, 16, 398, "DHL")
		fmt.Fprintf(&content, "0 0 0 rg 0 0 0 RG\n")
		text("F2", 15, 88, 404, "EXPRESS WORLDWIDE")
		text("F1", 8, 88, 390, "WAYBILL: "+tracking)
		line(8, 380, 280, 380, 1.5)
	}

	// Origin & Meta row (Y: 310 to 378)
	// Left side: SHIP FROM
	text("F2", 7, 14, 370, "SHIP FROM:")
	text("F1", 8, 14, 358, s.From.Name)
	if s.From.Company != "" && s.From.Company != s.From.Name {
		text("F1", 7.5, 14, 348, s.From.Company)
		text("F1", 7.5, 14, 338, s.From.Line1)
		text("F1", 7.5, 14, 328, strings.TrimSpace(s.From.City+", "+s.From.State+" "+s.From.PostalCode))
	} else {
		text("F1", 7.5, 14, 348, s.From.Line1)
		text("F1", 7.5, 14, 338, strings.TrimSpace(s.From.City+", "+s.From.State+" "+s.From.PostalCode))
		if s.From.Phone != "" {
			text("F1", 7, 14, 328, "TEL: "+s.From.Phone)
		}
	}

	// Right side metadata box (Weight, Ship date, Reference)
	line(170, 310, 170, 380, 0.8)
	totalLb := s.TotalWeightLb()
	if totalLb <= 0 {
		totalLb = 1.0
	}
	text("F1", 7, 175, 370, fmt.Sprintf("ACTUAL WT: %.1f LBS", totalLb))
	text("F1", 7, 175, 358, "DATE: "+time.Now().Format("02Jan06"))
	if s.Reference != "" {
		refDisp := s.Reference
		if len(refDisp) > 16 {
			refDisp = refDisp[:16] + "..."
		}
		text("F1", 7, 175, 346, "REF: "+refDisp)
	}
	text("F1", 7, 175, 334, "POSTAGE: PAID")
	text("F1", 6.5, 175, 322, "ORIGIN: "+s.OriginNode)

	// Horizontal dividing line above SHIP TO
	line(8, 308, 280, 308, 1.5)

	// SHIP TO Destination Address Box (Y: 205 to 306)
	text("F2", 8, 16, 294, "SHIP TO:")
	text("F2", 13, 30, 278, s.To.Name)
	if s.To.Company != "" && s.To.Company != s.To.Name {
		text("F1", 10, 30, 264, s.To.Company)
		text("F1", 10.5, 30, 250, s.To.Line1+" "+s.To.Line2)
		text("F2", 13, 30, 234, strings.ToUpper(strings.TrimSpace(s.To.City+", "+s.To.State+" "+s.To.PostalCode)))
	} else {
		text("F1", 11, 30, 262, s.To.Line1+" "+s.To.Line2)
		text("F2", 14, 30, 246, strings.ToUpper(strings.TrimSpace(s.To.City+", "+s.To.State+" "+s.To.PostalCode)))
	}
	if s.To.CountryOrUS() != "US" {
		text("F2", 13, 30, 220, s.To.CountryOrUS())
	}

	// Postal Routing Code / Zone Indicator (large bold destination ZIP box)
	rect(210, 225, 62, 38, 1.2)
	text("F1", 6.5, 215, 254, "DEST ZIP")
	text("F2", 14, 215, 236, s.To.ZIP5())

	// Horizontal dividing line above Postal Routing Barcode
	line(8, 204, 280, 204, 1.2)

	// Upper routing barcode (simulated Code 128 routing zip)
	zipDigits := s.To.ZIP5()
	if len(zipDigits) == 5 {
		drawSimulatedBarcode(&content, zipDigits, 44, 162, 200, 32)
		text("F1", 7.5, 105, 152, fmt.Sprintf("(420) %s", zipDigits))
	}

	// Heavy Horizontal line separating routing from primary tracking barcode
	line(8, 144, 280, 144, 2.0)

	// Carrier Tracking Title and Human-readable tracking number
	text("F2", 9, 14, 130, carrierUpper+" TRACKING NUMBER:")
	spacedTracking := formatTrackingReadable(tracking)
	text("F2", 12, 14, 116, spacedTracking)

	// Primary high-density Barcode (Y: 42 to 105)
	drawSimulatedBarcode(&content, tracking, 20, 48, 248, 62)

	// Bottom Footer Bar (Y: 8 to 36)
	line(8, 38, 280, 38, 1.0)
	text("F1", 6.5, 14, 26, "TEST SHIPMENT · NO COMMERCIAL VALUE · AFROTRANSACT LOGISTICS PORTAL")
	text("F1", 6, 14, 16, fmt.Sprintf("CARRIER: %s | SERVICE: %s | CREATED: %s", carrierUpper, cleanServiceName, time.Now().Format("2006-01-02 15:04")))

	objs := []string{
		"<< /Type /Catalog /Pages 2 0 R >>",
		"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
		"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 288 432] /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>",
		fmt.Sprintf("<< /Length %d >>\nstream\n%sendstream", content.Len(), content.String()),
		"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
		"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
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

// drawSimulatedBarcode generates a dense, realistic Code-128 style linear barcode
func drawSimulatedBarcode(buf *bytes.Buffer, data string, x, y, totalWidth, height float64) {
	// Generate deterministic sequence of narrow and wide bars and spaces
	// Using standard barcode quiet zones, start/stop patterns and data interleaving
	h := sha256.Sum256([]byte(data))
	pattern := make([]bool, 0, 160)

	// Start pattern
	pattern = append(pattern, true, true, false, true, false, false)

	// Data bars derived from string characters and SHA hash for realistic variation
	for i := 0; i < len(data); i++ {
		ch := int(data[i])
		hByte := int(h[i%len(h)])
		pattern = append(pattern, true, (ch%2 == 0), false, (hByte%2 == 1), true, false, (hByte%3 == 0))
	}

	// Stop pattern
	pattern = append(pattern, true, true, false, false, true, false, true, true)

	// Compute bar width to fit exact totalWidth
	barW := totalWidth / float64(len(pattern))
	curX := x
	for _, isBar := range pattern {
		if isBar {
			fmt.Fprintf(buf, "%.2f %.1f %.2f %.1f re f\n", curX, y, barW, height)
		}
		curX += barW
	}
}

// formatTrackingReadable splits a tracking string into grouped blocks like real labels
func formatTrackingReadable(s string) string {
	s = strings.TrimSpace(s)
	if len(s) <= 12 {
		return s
	}
	var parts []string
	for i := 0; i < len(s); i += 4 {
		end := i + 4
		if end > len(s) {
			end = len(s)
		}
		parts = append(parts, s[i:end])
	}
	return strings.Join(parts, " ")
}

func pdfEscape(s string) string {
	s = strings.NewReplacer(`\`, `\\`, "(", `\(`, ")", `\)`, "·", "-").Replace(s)
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r >= 32 && r < 127 {
			out = append(out, r)
		}
	}
	return string(out)
}
