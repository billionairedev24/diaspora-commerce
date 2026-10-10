// Package fedex implements port.Carrier with the FedEx REST APIs (OAuth client credentials, Rate quotes, Ship
// create/cancel, Track by tracking numbers, Pickup, Address Validation) and verifies FedEx tracking webhooks
// signed with HMAC-SHA256 over the raw body using the configured security token.
package fedex

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Paths are the FedEx endpoint paths (with API versions).
type Paths struct {
	Token   string `yaml:"token"`
	Rate    string `yaml:"rate"`
	Ship    string `yaml:"ship"`
	Cancel  string `yaml:"cancel"`
	Track   string `yaml:"track"`
	Pickup  string `yaml:"pickup"`
	Address string `yaml:"address"`
}

// DefaultPaths are the v1 endpoints.
func DefaultPaths() Paths {
	return Paths{ // #nosec G101 -- endpoint paths, not credentials
		Token:   "/oauth/token",
		Rate:    "/rate/v1/rates/quotes",
		Ship:    "/ship/v1/shipments",
		Cancel:  "/ship/v1/shipments/cancel",
		Track:   "/track/v1/trackingnumbers",
		Pickup:  "/pickup/v1/pickups",
		Address: "/address/v1/addresses/resolve",
	}
}

// Options configure the FedEx adapter.
type Options struct {
	BaseURL       string
	ClientID      string
	ClientSecret  string
	AccountNumber string
	Paths         Paths
	ImageType     string // PDF or ZPLII
	// WebhookSecret is the webhook project's security token; SignatureHeader defaults to "fdx-signature".
	WebhookSecret   string
	SignatureHeader string
	HTTP            *http.Client
	MaxRetries      int
	Now             func() time.Time
}

type fedexImpl struct {
	opts   Options
	client carrierhttp.Client
}

// Adapter is the FedEx carrier with webhook support.
type Adapter interface {
	port.Carrier
	port.WebhookReceiver
}

var _ Adapter = (*fedexImpl)(nil)

// New returns the FedEx adapter.
func New(o Options) Adapter {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.ImageType == "" {
		o.ImageType = "PDF"
	}
	if o.SignatureHeader == "" {
		o.SignatureHeader = "fdx-signature"
	}
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	tokenClient := carrierhttp.New(carrierhttp.Options{Carrier: domain.FedEx, HTTP: o.HTTP, MaxRetries: o.MaxRetries, ParseError: parseError})
	tokens := carrierhttp.NewTokenSource(func(ctx context.Context) (string, time.Duration, error) {
		res, err := tokenClient.Do(ctx, func(ctx context.Context) (*http.Request, error) {
			form := url.Values{"grant_type": {"client_credentials"}, "client_id": {o.ClientID}, "client_secret": {o.ClientSecret}}
			req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+o.Paths.Token, strings.NewReader(form.Encode()))
			if err != nil {
				return nil, fmt.Errorf("token request: %w", err)
			}
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			return req, nil
		})
		if err != nil {
			return "", 0, fmt.Errorf("oauth: %w", carrierhttp.AuthFailure(err))
		}
		var tok struct {
			AccessToken string              `json:"access_token"`
			ExpiresIn   carrierhttp.FlexInt `json:"expires_in"`
		}
		if err := decode(res.Body, &tok); err != nil {
			return "", 0, err
		}
		return tok.AccessToken, time.Duration(tok.ExpiresIn) * time.Second, nil
	}, o.Now)
	return &fedexImpl{opts: o, client: carrierhttp.New(carrierhttp.Options{
		Carrier: domain.FedEx, HTTP: o.HTTP, Auth: tokens, MaxRetries: o.MaxRetries, ParseError: parseError,
	})}
}

func (f *fedexImpl) Code() domain.CarrierCode { return domain.FedEx }

// parseError reads {"transactionId":…,"errors":[{"code","message"}]}.
func parseError(_ int, body []byte) (string, string) {
	var e struct {
		Errors []struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"errors"`
	}
	if json.Unmarshal(body, &e) != nil || len(e.Errors) == 0 {
		return "", ""
	}
	return e.Errors[0].Code, e.Errors[0].Message
}

func (f *fedexImpl) do(ctx context.Context, method, path string, body any) (carrierhttp.Response, error) {
	res, err := f.client.Do(ctx, func(ctx context.Context) (*http.Request, error) {
		req, err := jsonRequest(ctx, method, f.opts.BaseURL+path, body)
		if err != nil {
			return nil, err
		}
		req.Header.Set("X-locale", "en_US")
		req.Header.Set("x-customer-transaction-id", uuid.NewString())
		return req, nil
	})
	if err != nil {
		return carrierhttp.Response{}, fmt.Errorf("fedex %s: %w", path, err)
	}
	return res, nil
}

type value struct {
	Value string `json:"value"`
}

type fxAddress struct {
	StreetLines         []string `json:"streetLines,omitempty"`
	City                string   `json:"city,omitempty"`
	StateOrProvinceCode string   `json:"stateOrProvinceCode,omitempty"`
	PostalCode          string   `json:"postalCode"`
	CountryCode         string   `json:"countryCode"`
	Residential         bool     `json:"residential,omitempty"`
}

type contact struct {
	PersonName  string `json:"personName,omitempty"`
	CompanyName string `json:"companyName,omitempty"`
	PhoneNumber string `json:"phoneNumber,omitempty"`
	EmailAddr   string `json:"emailAddress,omitempty"`
}

type fxParty struct {
	Contact *contact  `json:"contact,omitempty"`
	Address fxAddress `json:"address"`
}

func toAddress(a domain.Address) fxAddress {
	lines := []string{a.Line1}
	if a.Line2 != "" {
		lines = append(lines, a.Line2)
	}
	return fxAddress{StreetLines: lines, City: a.City, StateOrProvinceCode: a.State, PostalCode: a.PostalCode,
		CountryCode: a.CountryOrUS(), Residential: a.Residential}
}

func toParty(a domain.Address, withContact bool) fxParty {
	p := fxParty{Address: toAddress(a)}
	if withContact {
		p.Contact = &contact{PersonName: a.Name, CompanyName: a.Company, PhoneNumber: a.Phone, EmailAddr: a.Email}
	}
	return p
}

type lineItem struct {
	Weight struct {
		Units string  `json:"units"`
		Value float64 `json:"value"`
	} `json:"weight"`
	Dimensions struct {
		Length int    `json:"length"`
		Width  int    `json:"width"`
		Height int    `json:"height"`
		Units  string `json:"units"`
	} `json:"dimensions"`
	CustomerReferences []map[string]string `json:"customerReferences,omitempty"`
}

func ceil(f float64) int {
	n := int(f)
	if float64(n) < f {
		n++
	}
	return n
}

func lineItems(ps []domain.Parcel, ref string) []lineItem {
	out := make([]lineItem, 0, len(ps))
	for _, p := range ps {
		var li lineItem
		li.Weight.Units, li.Weight.Value = "LB", p.WeightLb
		li.Dimensions.Units = "IN"
		li.Dimensions.Length, li.Dimensions.Width, li.Dimensions.Height = ceil(p.LengthIn), ceil(p.WidthIn), ceil(p.HeightIn)
		if ref != "" {
			li.CustomerReferences = []map[string]string{{"customerReferenceType": "CUSTOMER_REFERENCE", "value": ref}}
		}
		out = append(out, li)
	}
	return out
}

func (f *fedexImpl) account(a domain.Account) string {
	if a.Number != "" {
		return a.Number
	}
	return f.opts.AccountNumber
}

// RateReplyDetail is one service in a rate quote response.
type RateReplyDetail struct {
	ServiceType          string `json:"serviceType"`
	ServiceName          string `json:"serviceName"`
	RatedShipmentDetails []struct {
		RateType       string  `json:"rateType"`
		TotalNetCharge float64 `json:"totalNetCharge"`
		Currency       string  `json:"currency"`
	} `json:"ratedShipmentDetails"`
	Commit *struct {
		DateDetail *struct {
			DayFormat string `json:"dayFormat"`
		} `json:"dateDetail"`
	} `json:"commit"`
	OperationalDetail *struct {
		TransitTime string `json:"transitTime"`
	} `json:"operationalDetail"`
}

// Rate requests account (negotiated) and list rates for all services.
func (f *fedexImpl) Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error) {
	rateTypes := []string{"LIST"}
	if s.Account.Negotiated {
		rateTypes = []string{"ACCOUNT", "LIST"}
	}
	rs := map[string]any{
		"shipper":                   toParty(s.From, false),
		"recipient":                 toParty(s.To, false),
		"pickupType":                "USE_SCHEDULED_PICKUP",
		"rateRequestType":           rateTypes,
		"requestedPackageLineItems": lineItems(s.Parcels, ""),
	}
	if s.International() && s.Customs != nil {
		rs["customsClearanceDetail"] = customs(s)
	}
	body := map[string]any{
		"accountNumber":                value{Value: f.account(s.Account)},
		"rateRequestControlParameters": map[string]any{"returnTransitTimes": true},
		"requestedShipment":            rs,
	}
	res, err := f.do(ctx, http.MethodPost, f.opts.Paths.Rate, body)
	if err != nil {
		return nil, err
	}
	var out struct {
		Output struct {
			RateReplyDetails []RateReplyDetail `json:"rateReplyDetails"`
		} `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	return Quotes(out.Output.RateReplyDetails), nil
}

var transitDays = map[string]int{
	"ONE_DAY": 1, "TWO_DAYS": 2, "THREE_DAYS": 3, "FOUR_DAYS": 4, "FIVE_DAYS": 5, "SIX_DAYS": 6, "SEVEN_DAYS": 7,
	"EIGHT_DAYS": 8, "NINE_DAYS": 9, "TEN_DAYS": 10,
}

// Quotes converts rate reply details; the ACCOUNT rate (negotiated) wins over LIST when present.
func Quotes(details []RateReplyDetail) []domain.Quote {
	quotes := make([]domain.Quote, 0, len(details))
	for _, d := range details {
		q := domain.Quote{Carrier: domain.FedEx, Service: domain.ServiceCode(d.ServiceType), ServiceName: d.ServiceName, Currency: "USD"}
		for _, r := range d.RatedShipmentDetails {
			amt := domain.MinorFromFloat(r.TotalNetCharge)
			if r.Currency != "" {
				q.Currency = r.Currency
			}
			switch r.RateType {
			case "ACCOUNT":
				q.AmountMinor, q.Negotiated = amt, true
			case "LIST":
				q.ListAmountMinor = amt
			default:
				if q.ListAmountMinor == 0 {
					q.ListAmountMinor = amt
				}
			}
		}
		if q.AmountMinor == 0 {
			q.AmountMinor = q.ListAmountMinor
		}
		if q.AmountMinor == 0 {
			continue
		}
		if d.OperationalDetail != nil {
			q.TransitDays = transitDays[d.OperationalDetail.TransitTime]
		}
		if d.Commit != nil && d.Commit.DateDetail != nil {
			if t, err := time.Parse("2006-01-02T15:04:05", d.Commit.DateDetail.DayFormat); err == nil {
				q.EstimatedDelivery = &t
			}
		}
		quotes = append(quotes, q)
	}
	return quotes
}

func customs(s domain.Shipment) map[string]any {
	commodities := make([]map[string]any, 0, len(s.Customs.Lines))
	for _, l := range s.Customs.Lines {
		commodities = append(commodities, map[string]any{
			"description":          l.Description,
			"countryOfManufacture": l.OriginCountry,
			"quantity":             l.Quantity,
			"quantityUnits":        "PCS",
			"harmonizedCode":       l.HSCode,
			"unitPrice":            map[string]any{"amount": float64(l.UnitValueMinor) / 100, "currency": s.Customs.Currency},
			"customsValue":         map[string]any{"amount": float64(l.UnitValueMinor*int64(l.Quantity)) / 100, "currency": s.Customs.Currency},
			"weight":               map[string]any{"units": "LB", "value": l.WeightLb},
		})
	}
	payer := "RECIPIENT"
	if s.Customs.Incoterm == "DDP" {
		payer = "SENDER"
	}
	return map[string]any{
		"dutiesPayment":     map[string]string{"paymentType": payer},
		"isDocumentOnly":    false,
		"commodities":       commodities,
		"commercialInvoice": map[string]string{"shipmentPurpose": "SOLD"},
		"totalCustomsValue": map[string]any{"amount": float64(s.Customs.DeclaredValueMinor()) / 100, "currency": s.Customs.Currency},
	}
}

// ShipOutput is the create-shipment response output.
type ShipOutput struct {
	TransactionShipments []struct {
		MasterTrackingNumber string `json:"masterTrackingNumber"`
		ServiceType          string `json:"serviceType"`
		PieceResponses       []struct {
			TrackingNumber   string  `json:"trackingNumber"`
			NetChargeAmount  float64 `json:"netChargeAmount"`
			PackageDocuments []struct {
				ContentType  string `json:"contentType"`
				DocType      string `json:"docType"`
				EncodedLabel string `json:"encodedLabel"`
			} `json:"packageDocuments"`
		} `json:"pieceResponses"`
		CompletedShipmentDetail struct {
			ShipmentRating struct {
				ShipmentRateDetails []struct {
					RateType       string  `json:"rateType"`
					TotalNetCharge float64 `json:"totalNetCharge"`
					Currency       string  `json:"currency"`
				} `json:"shipmentRateDetails"`
			} `json:"shipmentRating"`
		} `json:"completedShipmentDetail"`
	} `json:"transactionShipments"`
}

// BuyLabel creates a shipment and returns the first package label.
func (f *fedexImpl) BuyLabel(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error) {
	stock := "PAPER_4X6"
	if f.opts.ImageType == "ZPLII" {
		stock = "STOCK_4X6"
	}
	shipDate := s.ShipDate
	if shipDate.IsZero() {
		shipDate = f.opts.Now()
	}
	rs := map[string]any{
		"shipper":                   toParty(s.From, true),
		"recipients":                []fxParty{toParty(s.To, true)},
		"shipDatestamp":             shipDate.Format(time.DateOnly),
		"serviceType":               string(service),
		"packagingType":             "YOUR_PACKAGING",
		"pickupType":                "USE_SCHEDULED_PICKUP",
		"shippingChargesPayment":    map[string]string{"paymentType": "SENDER"},
		"labelSpecification":        map[string]string{"imageType": f.opts.ImageType, "labelStockType": stock},
		"requestedPackageLineItems": lineItems(s.Parcels, s.Reference),
	}
	if s.Account.Negotiated {
		rs["rateRequestType"] = []string{"ACCOUNT"}
	}
	if s.International() && s.Customs != nil {
		rs["customsClearanceDetail"] = customs(s)
	}
	body := map[string]any{
		"labelResponseOptions": "LABEL",
		"accountNumber":        value{Value: f.account(s.Account)},
		"requestedShipment":    rs,
	}
	res, err := f.do(ctx, http.MethodPost, f.opts.Paths.Ship, body)
	if err != nil {
		return domain.Label{}, err
	}
	var out struct {
		Output ShipOutput `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Label{}, err
	}
	return labelFrom(out.Output, service, f.opts.ImageType)
}

func labelFrom(o ShipOutput, service domain.ServiceCode, imageType string) (domain.Label, error) {
	if len(o.TransactionShipments) == 0 || len(o.TransactionShipments[0].PieceResponses) == 0 {
		return domain.Label{}, rejected("NO_SHIPMENT", "ship response had no pieces")
	}
	ts := o.TransactionShipments[0]
	piece := ts.PieceResponses[0]
	var img []byte
	for _, d := range piece.PackageDocuments {
		if d.EncodedLabel != "" {
			b, err := base64.StdEncoding.DecodeString(d.EncodedLabel)
			if err == nil {
				img = b
				break
			}
		}
	}
	if len(img) == 0 {
		return domain.Label{}, rejected("NO_LABEL", "ship response had no encoded label")
	}
	cost, currency := domain.MinorFromFloat(piece.NetChargeAmount), "USD"
	if rd := ts.CompletedShipmentDetail.ShipmentRating.ShipmentRateDetails; len(rd) > 0 {
		cost = domain.MinorFromFloat(rd[0].TotalNetCharge)
		if rd[0].Currency != "" {
			currency = rd[0].Currency
		}
	}
	format := domain.FormatPDF
	if imageType == "ZPLII" {
		format = domain.FormatZPL
	}
	return domain.Label{Carrier: domain.FedEx, Service: service, TrackingNumber: piece.TrackingNumber,
		CarrierShipmentID: ts.MasterTrackingNumber, CostMinor: cost, Currency: currency, Format: format, Data: img}, nil
}

// Void cancels a shipment (all packages) before it is tendered.
func (f *fedexImpl) Void(ctx context.Context, trackingNumber string) error {
	body := map[string]any{
		"accountNumber":     value{Value: f.opts.AccountNumber},
		"emailShipment":     false,
		"senderCountryCode": "US",
		"deletionControl":   "DELETE_ALL_PACKAGES",
		"trackingNumber":    trackingNumber,
	}
	res, err := f.do(ctx, http.MethodPut, f.opts.Paths.Cancel, body)
	if err != nil {
		return err
	}
	var out struct {
		Output struct {
			CancelledShipment bool   `json:"cancelledShipment"`
			Message           string `json:"message"`
		} `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return err
	}
	if !out.Output.CancelledShipment {
		return rejected("NOT_CANCELLED", out.Output.Message)
	}
	return nil
}

// TrackOutput is the Track API output (also the shape FedEx pushes to tracking webhooks).
type TrackOutput struct {
	CompleteTrackResults []struct {
		TrackingNumber string        `json:"trackingNumber"`
		TrackResults   []TrackResult `json:"trackResults"`
	} `json:"completeTrackResults"`
}

// TrackResult is one tracking number's result.
type TrackResult struct {
	TrackingNumberInfo struct {
		TrackingNumber string `json:"trackingNumber"`
	} `json:"trackingNumberInfo"`
	ScanEvents []ScanEvent `json:"scanEvents"`
	Error      *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// ScanEvent is one FedEx scan.
type ScanEvent struct {
	Date              string `json:"date"`
	EventType         string `json:"eventType"`
	EventDescription  string `json:"eventDescription"`
	DerivedStatusCode string `json:"derivedStatusCode"`
	DerivedStatus     string `json:"derivedStatus"`
	ScanLocation      struct {
		City                string `json:"city"`
		StateOrProvinceCode string `json:"stateOrProvinceCode"`
		PostalCode          string `json:"postalCode"`
		CountryCode         string `json:"countryCode"`
	} `json:"scanLocation"`
}

// Track fetches detailed scans for a tracking number.
func (f *fedexImpl) Track(ctx context.Context, trackingNumber string) ([]domain.TrackingEvent, error) {
	body := map[string]any{
		"includeDetailedScans": true,
		"trackingInfo":         []map[string]any{{"trackingNumberInfo": map[string]string{"trackingNumber": trackingNumber}}},
	}
	res, err := f.do(ctx, http.MethodPost, f.opts.Paths.Track, body)
	if err != nil {
		return nil, err
	}
	var out struct {
		Output TrackOutput `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	for _, c := range out.Output.CompleteTrackResults {
		for _, r := range c.TrackResults {
			if r.Error != nil && strings.Contains(r.Error.Code, "NOTFOUND") {
				return nil, &domain.CarrierError{Carrier: domain.FedEx, Code: r.Error.Code, Message: r.Error.Message, Kind: domain.ErrNotFound}
			}
		}
	}
	return Events(out.Output), nil
}

// Events normalises track output (newest scan first in FedEx responses).
func Events(o TrackOutput) []domain.TrackingEvent {
	var evs []domain.TrackingEvent
	for _, c := range o.CompleteTrackResults {
		for _, r := range c.TrackResults {
			tn := r.TrackingNumberInfo.TrackingNumber
			if tn == "" {
				tn = c.TrackingNumber
			}
			n := len(r.ScanEvents)
			for i, e := range r.ScanEvents {
				at, _ := time.Parse(time.RFC3339, e.Date)
				loc := e.ScanLocation
				code := firstNonEmpty(e.EventType, e.DerivedStatusCode)
				evs = append(evs, domain.TrackingEvent{
					Carrier: domain.FedEx, TrackingNumber: tn,
					EventID:     domain.EventKey("fedex", tn, at.UTC().Format(time.RFC3339), e.EventType, loc.PostalCode),
					Code:        code,
					Description: firstNonEmpty(e.EventDescription, e.DerivedStatus),
					State:       MapStatus(code),
					OccurredAt:  at.UTC(),
					Location:    strings.Trim(strings.Join([]string{loc.City, loc.StateOrProvinceCode, loc.CountryCode}, ", "), ", "),
					Sequence:    n - 1 - i,
				})
			}
		}
	}
	domain.SortEvents(evs)
	return evs
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if v != "" {
			return v
		}
	}
	return ""
}

// MapStatus maps FedEx scan event types to our state.
func MapStatus(eventType string) domain.ShipmentState {
	switch strings.ToUpper(eventType) {
	case "OC", "IN":
		return domain.StateLabelCreated
	case "PU", "PX":
		return domain.StatePickedUp
	case "OD":
		return domain.StateOutForDelivery
	case "DL":
		return domain.StateDelivered
	case "DE":
		return domain.StateDeliveryFailed
	case "RS", "RP":
		return domain.StateReturned
	case "SE", "CA", "DY", "CD":
		return domain.StateException
	case "IT", "AR", "DP", "AF", "AA", "AD", "CC", "CP", "EA", "EP", "FD", "HL", "LO", "OF", "PF", "PM", "SF", "TR", "AP", "PL":
		return domain.StateInTransit
	default:
		return ""
	}
}

// VerifyWebhook checks the HMAC-SHA256 signature FedEx sends in the signature header (base64, hex accepted).
func (f *fedexImpl) VerifyWebhook(h http.Header, body []byte) error {
	if !carrierhttp.VerifyHMAC(f.opts.WebhookSecret, h.Get(f.opts.SignatureHeader), body) {
		return domain.ErrSignature
	}
	return nil
}

// ParseWebhook reads a pushed tracking document. FedEx pushes the Track API result shape, either wrapped in
// {"output":{…}} or bare ({"completeTrackResults":[…]}).
func (f *fedexImpl) ParseWebhook(body []byte) ([]domain.TrackingEvent, error) {
	var wrapped struct {
		Output *TrackOutput `json:"output"`
		TrackOutput
	}
	if err := json.Unmarshal(body, &wrapped); err != nil {
		return nil, fmt.Errorf("%w: fedex webhook: %w", domain.ErrInvalid, err)
	}
	if wrapped.Output != nil {
		return Events(*wrapped.Output), nil
	}
	return Events(wrapped.TrackOutput), nil
}

// SchedulePickup books a FedEx Ground (FDXG) or Express (FDXE) pickup.
func (f *fedexImpl) SchedulePickup(ctx context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	carrierCode := "FDXG"
	if p.Service != "" && !strings.Contains(string(p.Service), "GROUND") {
		carrierCode = "FDXE"
	}
	dateType := "FUTURE_DAY"
	if p.Date.Format(time.DateOnly) == f.opts.Now().Format(time.DateOnly) {
		dateType = "SAME_DAY"
	}
	body := map[string]any{
		"associatedAccountNumber": value{Value: f.account(p.Account)},
		"originDetail": map[string]any{
			"pickupLocation":     toParty(p.Address, true),
			"readyDateTimestamp": p.Date.Format(time.DateOnly) + "T" + p.ReadyTime + ":00Z",
			"customerCloseTime":  p.CloseTime + ":00",
			"pickupDateType":     dateType,
			"packageLocation":    "FRONT",
		},
		"carrierCode":  carrierCode,
		"totalWeight":  map[string]any{"units": "LB", "value": p.TotalWeightLb},
		"packageCount": p.Packages,
		"remarks":      p.Location,
	}
	res, err := f.do(ctx, http.MethodPost, f.opts.Paths.Pickup, body)
	if err != nil {
		return domain.Pickup{}, err
	}
	var out struct {
		Output struct {
			PickupConfirmationCode string `json:"pickupConfirmationCode"`
		} `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Pickup{}, err
	}
	return domain.Pickup{Carrier: domain.FedEx, Confirmation: out.Output.PickupConfirmationCode, Date: p.Date}, nil
}

// ResolvedAddress is one result of Address Validation.
type ResolvedAddress struct {
	StreetLinesToken    []string          `json:"streetLinesToken"`
	City                string            `json:"city"`
	StateOrProvinceCode string            `json:"stateOrProvinceCode"`
	PostalCode          string            `json:"postalCode"`
	CountryCode         string            `json:"countryCode"`
	Classification      string            `json:"classification"`
	Attributes          map[string]string `json:"attributes"`
}

// ValidateAddress resolves an address and reports classification and DPV.
func (f *fedexImpl) ValidateAddress(ctx context.Context, a domain.Address) (domain.AddressValidation, error) {
	body := map[string]any{"addressesToValidate": []map[string]any{{"address": toAddress(a)}}}
	res, err := f.do(ctx, http.MethodPost, f.opts.Paths.Address, body)
	if err != nil {
		return domain.AddressValidation{}, err
	}
	var out struct {
		Output struct {
			ResolvedAddresses []ResolvedAddress `json:"resolvedAddresses"`
		} `json:"output"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.AddressValidation{}, err
	}
	return InterpretAddress(out.Output.ResolvedAddresses), nil
}

// InterpretAddress maps the first resolved address.
func InterpretAddress(rs []ResolvedAddress) domain.AddressValidation {
	v := domain.AddressValidation{Carrier: domain.FedEx, Messages: []string{}}
	if len(rs) == 0 {
		v.Messages = append(v.Messages, "FedEx could not resolve this address.")
		return v
	}
	r := rs[0]
	std := domain.Address{City: r.City, State: r.StateOrProvinceCode, PostalCode: r.PostalCode, Country: r.CountryCode}
	if len(r.StreetLinesToken) > 0 {
		std.Line1 = r.StreetLinesToken[0]
	}
	if len(r.StreetLinesToken) > 1 {
		std.Line2 = r.StreetLinesToken[1]
	}
	switch r.Classification {
	case "RESIDENTIAL":
		t := true
		v.Residential, std.Residential = &t, true
	case "BUSINESS":
		f := false
		v.Residential = &f
	}
	v.Standardized = &std
	v.Valid = r.Attributes["Resolved"] == "true" && r.Attributes["DPV"] == "true"
	if r.Attributes["SuiteRequiredButMissing"] == "true" {
		v.Valid = false
		v.Messages = append(v.Messages, "Missing apartment, suite or unit number.")
	}
	if r.Attributes["InvalidSuiteNumber"] == "true" {
		v.Messages = append(v.Messages, "The suite number could not be confirmed.")
	}
	if !v.Valid && len(v.Messages) == 0 {
		v.Messages = append(v.Messages, "FedEx could not confirm this address is deliverable.")
	}
	return v
}
