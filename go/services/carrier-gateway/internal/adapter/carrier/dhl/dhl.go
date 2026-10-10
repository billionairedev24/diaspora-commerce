// Package dhl implements port.Carrier with the DHL Express MyDHL API (HTTP Basic auth): rates, shipments with
// customs data for international parcels (commercial invoice lines, HS codes, declared value), tracking,
// pickups and address (serviceability) validation, plus verification of DHL tracking pushes.
package dhl

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
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

// Paths are the MyDHL API paths (relative to the versioned base URL).
type Paths struct {
	Rates     string `yaml:"rates"`
	Shipments string `yaml:"shipments"`
	Track     string `yaml:"track"`
	Pickups   string `yaml:"pickups"`
	Address   string `yaml:"address"`
}

// DefaultPaths are the MyDHL API v2 paths.
func DefaultPaths() Paths {
	return Paths{
		Rates:     "/rates",
		Shipments: "/shipments",
		Track:     "/shipments/{trackingNumber}/tracking",
		Pickups:   "/pickups",
		Address:   "/address-validate",
	}
}

// Options configure the DHL adapter.
type Options struct {
	BaseURL       string // https://express.api.dhl.com/mydhlapi (test: …/mydhlapi/test)
	Username      string
	Password      string
	AccountNumber string
	Paths         Paths
	LabelFormat   string // pdf or zpl
	// WebhookSecret signs pushes with HMAC-SHA256 in SignatureHeader (default "X-DHL-Signature").
	WebhookSecret   string
	SignatureHeader string
	HTTP            *http.Client
	MaxRetries      int
	Now             func() time.Time
}

type dhlImpl struct {
	opts   Options
	client carrierhttp.Client
}

// Adapter is the DHL carrier with push support.
type Adapter interface {
	port.Carrier
	port.WebhookReceiver
}

var _ Adapter = (*dhlImpl)(nil)

// New returns the DHL adapter.
func New(o Options) Adapter {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.LabelFormat == "" {
		o.LabelFormat = "pdf"
	}
	if o.SignatureHeader == "" {
		o.SignatureHeader = "X-DHL-Signature"
	}
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	return &dhlImpl{opts: o, client: carrierhttp.New(carrierhttp.Options{
		Carrier: domain.DHL, HTTP: o.HTTP, Auth: carrierhttp.BasicAuth(o.Username, o.Password),
		MaxRetries: o.MaxRetries, ParseError: parseError,
	})}
}

func (d *dhlImpl) Code() domain.CarrierCode { return domain.DHL }

// parseError reads the MyDHL problem body {"title","detail","status","additionalDetails":[…]}.
func parseError(_ int, body []byte) (string, string) {
	var e struct {
		Title             string   `json:"title"`
		Detail            string   `json:"detail"`
		Message           string   `json:"message"`
		AdditionalDetails []string `json:"additionalDetails"`
	}
	if json.Unmarshal(body, &e) != nil {
		return "", ""
	}
	msg := firstNonEmpty(e.Detail, e.Message)
	if len(e.AdditionalDetails) > 0 {
		msg += " (" + strings.Join(e.AdditionalDetails, "; ") + ")"
	}
	return e.Title, msg
}

func (d *dhlImpl) do(ctx context.Context, method, path string, body any) (carrierhttp.Response, error) {
	res, err := d.client.Do(ctx, func(ctx context.Context) (*http.Request, error) {
		req, err := jsonRequest(ctx, method, d.opts.BaseURL+path, body)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Message-Reference", uuid.NewString())
		req.Header.Set("Message-Reference-Date", d.opts.Now().UTC().Format(http.TimeFormat))
		return req, nil
	})
	if err != nil {
		return carrierhttp.Response{}, fmt.Errorf("dhl %s: %w", path, err)
	}
	return res, nil
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if v != "" {
			return v
		}
	}
	return ""
}

func (d *dhlImpl) account(a domain.Account) string {
	if a.Number != "" {
		return a.Number
	}
	return d.opts.AccountNumber
}

// plannedTime formats a ship date the way MyDHL wants it: "2026-10-10T13:00:00 GMT-05:00".
func (d *dhlImpl) plannedTime(t time.Time) string {
	if t.IsZero() {
		t = d.opts.Now()
	}
	t = time.Date(t.Year(), t.Month(), t.Day(), 13, 0, 0, 0, t.Location())
	return t.Format("2006-01-02T15:04:05") + " GMT" + t.Format("-07:00")
}

type postal struct {
	PostalCode   string `json:"postalCode"`
	CityName     string `json:"cityName"`
	CountryCode  string `json:"countryCode"`
	ProvinceCode string `json:"provinceCode,omitempty"`
	AddressLine1 string `json:"addressLine1"`
	AddressLine2 string `json:"addressLine2,omitempty"`
}

func toPostal(a domain.Address) postal {
	return postal{PostalCode: a.PostalCode, CityName: a.City, CountryCode: a.CountryOrUS(), ProvinceCode: a.State,
		AddressLine1: a.Line1, AddressLine2: a.Line2}
}

type dhlPackage struct {
	Weight     float64 `json:"weight"`
	Dimensions struct {
		Length float64 `json:"length"`
		Width  float64 `json:"width"`
		Height float64 `json:"height"`
	} `json:"dimensions"`
}

func pkgs(ps []domain.Parcel) []dhlPackage {
	out := make([]dhlPackage, 0, len(ps))
	for _, p := range ps {
		x := dhlPackage{Weight: p.WeightLb}
		x.Dimensions.Length, x.Dimensions.Width, x.Dimensions.Height = p.LengthIn, p.WidthIn, p.HeightIn
		out = append(out, x)
	}
	return out
}

// Product is one product in a rates response.
type Product struct {
	ProductName      string `json:"productName"`
	ProductCode      string `json:"productCode"`
	LocalProductCode string `json:"localProductCode"`
	TotalPrice       []struct {
		CurrencyType  string  `json:"currencyType"`
		PriceCurrency string  `json:"priceCurrency"`
		Price         float64 `json:"price"`
	} `json:"totalPrice"`
	DeliveryCapabilities struct {
		EstimatedDeliveryDateAndTime string              `json:"estimatedDeliveryDateAndTime"`
		TotalTransitDays             carrierhttp.FlexInt `json:"totalTransitDays"`
	} `json:"deliveryCapabilities"`
}

// Rate requests landed product prices for the account (customs-declarable for international).
func (d *dhlImpl) Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error) {
	body := map[string]any{
		"customerDetails": map[string]any{
			"shipperDetails":  map[string]string{"postalCode": s.From.PostalCode, "cityName": s.From.City, "countryCode": s.From.CountryOrUS(), "addressLine1": s.From.Line1},
			"receiverDetails": map[string]string{"postalCode": s.To.PostalCode, "cityName": s.To.City, "countryCode": s.To.CountryOrUS(), "addressLine1": s.To.Line1},
		},
		"accounts":                   []map[string]string{{"typeCode": "shipper", "number": d.account(s.Account)}},
		"plannedShippingDateAndTime": d.plannedTime(s.ShipDate),
		"unitOfMeasurement":          "imperial",
		"isCustomsDeclarable":        s.International(),
		"packages":                   pkgs(s.Parcels),
	}
	if s.International() && s.Customs != nil {
		body["monetaryAmount"] = []map[string]any{{"typeCode": "declaredValue", "value": float64(s.Customs.DeclaredValueMinor()) / 100, "currency": s.Customs.Currency}}
	}
	res, err := d.do(ctx, http.MethodPost, d.opts.Paths.Rates, body)
	if err != nil {
		return nil, err
	}
	var out struct {
		Products []Product `json:"products"`
	}
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	return Quotes(out.Products, s.Account.Negotiated), nil
}

// Quotes converts products, using the billing-currency (BILLC) price.
func Quotes(products []Product, negotiated bool) []domain.Quote {
	quotes := make([]domain.Quote, 0, len(products))
	for _, p := range products {
		var amount float64
		currency := ""
		for _, tp := range p.TotalPrice {
			if tp.CurrencyType == "BILLC" || currency == "" {
				amount, currency = tp.Price, tp.PriceCurrency
			}
		}
		if amount <= 0 {
			continue
		}
		q := domain.Quote{Carrier: domain.DHL, Service: domain.ServiceCode(p.ProductCode), ServiceName: "DHL " + p.ProductName,
			AmountMinor: domain.MinorFromFloat(amount), Currency: currency, Negotiated: negotiated,
			TransitDays: int(p.DeliveryCapabilities.TotalTransitDays)}
		q.ListAmountMinor = q.AmountMinor
		if t, err := time.Parse("2006-01-02T15:04:05", p.DeliveryCapabilities.EstimatedDeliveryDateAndTime); err == nil {
			q.EstimatedDelivery = &t
		}
		quotes = append(quotes, q)
	}
	return quotes
}

func contactOf(a domain.Address) map[string]string {
	return map[string]string{"fullName": a.Name, "companyName": firstNonEmpty(a.Company, a.Name), "phone": a.Phone, "email": a.Email}
}

func (d *dhlImpl) exportDeclaration(s domain.Shipment) map[string]any {
	lines := make([]map[string]any, 0, len(s.Customs.Lines))
	for i, l := range s.Customs.Lines {
		lines = append(lines, map[string]any{
			"number":              i + 1,
			"description":         l.Description,
			"price":               float64(l.UnitValueMinor) / 100,
			"quantity":            map[string]any{"value": l.Quantity, "unitOfMeasurement": "PCS"},
			"commodityCodes":      []map[string]string{{"typeCode": "outbound", "value": l.HSCode}},
			"exportReasonType":    "permanent",
			"manufacturerCountry": l.OriginCountry,
			"weight":              map[string]float64{"netValue": l.WeightLb, "grossValue": l.WeightLb},
		})
	}
	date := s.ShipDate
	if date.IsZero() {
		date = d.opts.Now()
	}
	return map[string]any{
		"lineItems":    lines,
		"invoice":      map[string]string{"number": firstNonEmpty(s.Customs.InvoiceNumber, s.Reference), "date": date.Format(time.DateOnly)},
		"exportReason": firstNonEmpty(s.Customs.ExportReason, "sale"),
	}
}

// ShipmentResponse is the create-shipment response.
type ShipmentResponse struct {
	ShipmentTrackingNumber string `json:"shipmentTrackingNumber"`
	Packages               []struct {
		TrackingNumber string `json:"trackingNumber"`
	} `json:"packages"`
	Documents []struct {
		ImageFormat string `json:"imageFormat"`
		Content     string `json:"content"`
		TypeCode    string `json:"typeCode"`
	} `json:"documents"`
	ShipmentCharges []struct {
		CurrencyType  string  `json:"currencyType"`
		PriceCurrency string  `json:"priceCurrency"`
		Price         float64 `json:"price"`
	} `json:"shipmentCharges"`
}

// BuyLabel creates a shipment (waybill + label; commercial invoice data for international parcels).
func (d *dhlImpl) BuyLabel(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error) {
	content := map[string]any{
		"packages":            pkgs(s.Parcels),
		"isCustomsDeclarable": s.International(),
		"description":         firstNonEmpty(s.Reference, "AfroTransact order"),
		"unitOfMeasurement":   "imperial",
	}
	if s.International() && s.Customs != nil {
		content["declaredValue"] = float64(s.Customs.DeclaredValueMinor()) / 100
		content["declaredValueCurrency"] = s.Customs.Currency
		content["incoterm"] = firstNonEmpty(s.Customs.Incoterm, "DAP")
		content["exportDeclaration"] = d.exportDeclaration(s)
	}
	body := map[string]any{
		"plannedShippingDateAndTime": d.plannedTime(s.ShipDate),
		"pickup":                     map[string]bool{"isRequested": false},
		"productCode":                string(service),
		"accounts":                   []map[string]string{{"typeCode": "shipper", "number": d.account(s.Account)}},
		"outputImageProperties": map[string]any{
			"encodingFormat": d.opts.LabelFormat,
			"imageOptions":   []map[string]any{{"typeCode": "label", "templateName": "ECOM26_84_001"}, {"typeCode": "invoice", "isRequested": s.International()}},
		},
		"customerDetails": map[string]any{
			"shipperDetails":  map[string]any{"postalAddress": toPostal(s.From), "contactInformation": contactOf(s.From)},
			"receiverDetails": map[string]any{"postalAddress": toPostal(s.To), "contactInformation": contactOf(s.To)},
		},
		"content": content,
	}
	if s.Reference != "" {
		body["customerReferences"] = []map[string]string{{"value": s.Reference, "typeCode": "CU"}}
	}
	res, err := d.do(ctx, http.MethodPost, d.opts.Paths.Shipments, body)
	if err != nil {
		return domain.Label{}, err
	}
	var out ShipmentResponse
	if err := decode(res.Body, &out); err != nil {
		return domain.Label{}, err
	}
	return labelFrom(out, service)
}

func labelFrom(r ShipmentResponse, service domain.ServiceCode) (domain.Label, error) {
	var img []byte
	format := domain.FormatPDF
	for _, doc := range r.Documents {
		if doc.TypeCode != "label" {
			continue
		}
		b, err := base64.StdEncoding.DecodeString(doc.Content)
		if err != nil {
			continue
		}
		img = b
		if strings.EqualFold(doc.ImageFormat, "ZPL") {
			format = domain.FormatZPL
		}
	}
	if r.ShipmentTrackingNumber == "" || len(img) == 0 {
		return domain.Label{}, rejected("NO_LABEL", "shipment response had no waybill or label")
	}
	var cost float64
	currency := "USD"
	for _, c := range r.ShipmentCharges {
		if c.CurrencyType == "BILLC" || cost == 0 {
			cost, currency = c.Price, firstNonEmpty(c.PriceCurrency, currency)
		}
	}
	return domain.Label{Carrier: domain.DHL, Service: service, TrackingNumber: r.ShipmentTrackingNumber,
		CarrierShipmentID: r.ShipmentTrackingNumber, CostMinor: domain.MinorFromFloat(cost), Currency: currency,
		Format: format, Data: img}, nil
}

// Void: the MyDHL API has no shipment cancellation; DHL bills only waybills it scans, so an unused label is void
// once we stop using it. Nothing is sent to DHL.
func (d *dhlImpl) Void(context.Context, string) error { return nil }

// TrackingResponse is the tracking response (also the push payload).
type TrackingResponse struct {
	Shipments []struct {
		ShipmentTrackingNumber string `json:"shipmentTrackingNumber"`
		Events                 []struct {
			Date        string `json:"date"`
			Time        string `json:"time"`
			GMTOffset   string `json:"GMTOffset"`
			TypeCode    string `json:"typeCode"`
			Description string `json:"description"`
			ServiceArea []struct {
				Code        string `json:"code"`
				Description string `json:"description"`
			} `json:"serviceArea"`
		} `json:"events"`
	} `json:"shipments"`
}

// Track fetches all checkpoints for a waybill.
func (d *dhlImpl) Track(ctx context.Context, trackingNumber string) ([]domain.TrackingEvent, error) {
	path := carrierhttp.Path(d.opts.Paths.Track, map[string]string{"trackingNumber": trackingNumber}) +
		"?trackingView=all-checkpoints&levelOfDetail=all"
	res, err := d.do(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	var out TrackingResponse
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	return Events(out), nil
}

// Events normalises tracking checkpoints (chronological in MyDHL responses).
func Events(r TrackingResponse) []domain.TrackingEvent {
	var evs []domain.TrackingEvent
	for _, sh := range r.Shipments {
		for i, e := range sh.Events {
			at := checkpointTime(e.Date, e.Time, e.GMTOffset)
			loc := ""
			if len(e.ServiceArea) > 0 {
				loc = e.ServiceArea[0].Description
			}
			evs = append(evs, domain.TrackingEvent{
				Carrier: domain.DHL, TrackingNumber: sh.ShipmentTrackingNumber,
				EventID:     domain.EventKey("dhl", sh.ShipmentTrackingNumber, at.UTC().Format(time.RFC3339), e.TypeCode, loc),
				Code:        e.TypeCode,
				Description: e.Description,
				State:       MapStatus(e.TypeCode),
				OccurredAt:  at,
				Location:    loc,
				Sequence:    i,
			})
		}
	}
	domain.SortEvents(evs)
	return evs
}

func checkpointTime(date, clock, offset string) time.Time {
	if offset == "" {
		offset = "+00:00"
	}
	if t, err := time.Parse("2006-01-02T15:04:05-07:00", date+"T"+clock+offset); err == nil {
		return t.UTC()
	}
	t, _ := time.Parse("2006-01-02T15:04:05", date+"T"+clock)
	return t.UTC()
}

// MapStatus maps DHL Express checkpoint codes to our state.
func MapStatus(code string) domain.ShipmentState {
	switch strings.ToUpper(code) {
	case "PU":
		return domain.StatePickedUp
	case "WC":
		return domain.StateOutForDelivery
	case "OK", "DD":
		return domain.StateDelivered
	case "NH", "CA", "CC", "AD":
		return domain.StateDeliveryFailed
	case "RT":
		return domain.StateReturned
	case "OH", "HP", "MS", "BA", "RD", "CM", "DM", "SS":
		return domain.StateException
	case "PL", "DF", "AF", "AR", "TR", "CR", "BN", "RR", "TP", "TD", "FD", "HI", "HO", "IC", "PO", "RW":
		return domain.StateInTransit
	default:
		return ""
	}
}

// VerifyWebhook checks the HMAC-SHA256 signature of a DHL push.
func (d *dhlImpl) VerifyWebhook(h http.Header, body []byte) error {
	if !carrierhttp.VerifyHMAC(d.opts.WebhookSecret, h.Get(d.opts.SignatureHeader), body) {
		return domain.ErrSignature
	}
	return nil
}

// ParseWebhook reads a push in the tracking response shape.
func (d *dhlImpl) ParseWebhook(body []byte) ([]domain.TrackingEvent, error) {
	var r TrackingResponse
	if err := json.Unmarshal(body, &r); err != nil {
		return nil, fmt.Errorf("%w: dhl push: %w", domain.ErrInvalid, err)
	}
	return Events(r), nil
}

// SchedulePickup books a courier pickup and returns the dispatch confirmation number.
func (d *dhlImpl) SchedulePickup(ctx context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	ready, err := time.ParseInLocation("2006-01-02 15:04", p.Date.Format(time.DateOnly)+" "+p.ReadyTime, p.Date.Location())
	if err != nil {
		return domain.Pickup{}, fmt.Errorf("%w: ready time: %w", domain.ErrInvalid, err)
	}
	locType := "business"
	if p.Address.Residential {
		locType = "residence"
	}
	product := firstNonEmpty(string(p.Service), "P")
	body := map[string]any{
		"plannedPickupDateAndTime": ready.Format("2006-01-02T15:04:05") + " GMT" + ready.Format("-07:00"),
		"closeTime":                p.CloseTime,
		"location":                 firstNonEmpty(p.Location, "reception"),
		"locationType":             locType,
		"accounts":                 []map[string]string{{"typeCode": "shipper", "number": d.account(p.Account)}},
		"customerDetails": map[string]any{
			"shipperDetails": map[string]any{"postalAddress": toPostal(p.Address), "contactInformation": contactOf(p.Address)},
		},
		"shipmentDetails": []map[string]any{{
			"productCode": product, "isCustomsDeclarable": p.International, "unitOfMeasurement": "imperial",
			"packages": []map[string]any{{"weight": p.TotalWeightLb / float64(max(p.Packages, 1)), "dimensions": map[string]int{"length": 1, "width": 1, "height": 1}}},
		}},
	}
	res, err := d.do(ctx, http.MethodPost, d.opts.Paths.Pickups, body)
	if err != nil {
		return domain.Pickup{}, err
	}
	var out struct {
		DispatchConfirmationNumbers []string `json:"dispatchConfirmationNumbers"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Pickup{}, err
	}
	if len(out.DispatchConfirmationNumbers) == 0 {
		return domain.Pickup{}, rejected("NO_CONFIRMATION", "pickup response had no dispatch confirmation number")
	}
	return domain.Pickup{Carrier: domain.DHL, Confirmation: out.DispatchConfirmationNumbers[0], Date: p.Date}, nil
}

// ValidateAddress checks that DHL Express serves the postal code and city (MyDHL validates serviceability,
// not street-level deliverability).
func (d *dhlImpl) ValidateAddress(ctx context.Context, a domain.Address) (domain.AddressValidation, error) {
	q := url.Values{"type": {"delivery"}, "countryCode": {a.CountryOrUS()}, "postalCode": {a.PostalCode}, "strictValidation": {"true"}}
	if a.City != "" {
		q.Set("cityName", a.City)
	}
	v := domain.AddressValidation{Carrier: domain.DHL, Messages: []string{"DHL checks city and postal code serviceability only."}}
	res, err := d.do(ctx, http.MethodGet, d.opts.Paths.Address+"?"+q.Encode(), nil)
	if errors.Is(err, domain.ErrNotFound) {
		v.Messages = append(v.Messages, "DHL Express does not serve this city and postal code.")
		return v, nil
	}
	if err != nil {
		return domain.AddressValidation{}, err
	}
	var out struct {
		Address []struct {
			CountryCode string `json:"countryCode"`
			PostalCode  string `json:"postalCode"`
			CityName    string `json:"cityName"`
		} `json:"address"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.AddressValidation{}, err
	}
	if len(out.Address) == 0 {
		v.Messages = append(v.Messages, "DHL Express does not serve this city and postal code.")
		return v, nil
	}
	v.Valid = true
	std := a
	std.City, std.PostalCode, std.Country = out.Address[0].CityName, out.Address[0].PostalCode, out.Address[0].CountryCode
	v.Standardized = &std
	return v, nil
}
