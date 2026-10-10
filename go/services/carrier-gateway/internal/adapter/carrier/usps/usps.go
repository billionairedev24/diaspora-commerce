// Package usps implements port.Carrier with the USPS APIs at apis.usps.com (OAuth 2.0 v3, Domestic Prices v3,
// Labels v3, Tracking v3, Addresses v3, Carrier Pickup v3). USPS offers no tracking push we use, so the
// gateway polls Track for active labels.
package usps

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Paths are the USPS endpoint paths (versions live here so they change by configuration).
type Paths struct {
	Token   string `yaml:"token"`
	Prices  string `yaml:"prices"`
	Label   string `yaml:"label"`
	Void    string `yaml:"void"`
	Track   string `yaml:"track"`
	Address string `yaml:"address"`
	Pickup  string `yaml:"pickup"`
}

// DefaultPaths are the v3 endpoints.
func DefaultPaths() Paths {
	return Paths{ // #nosec G101 -- endpoint paths, not credentials
		Token:   "/oauth2/v3/token",
		Prices:  "/prices/v3/base-rates/search",
		Label:   "/labels/v3/label",
		Void:    "/labels/v3/label/{trackingNumber}",
		Track:   "/tracking/v3/tracking/{trackingNumber}",
		Address: "/addresses/v3/address",
		Pickup:  "/pickup/v3/carrier-pickup",
	}
}

// Options configure the USPS adapter.
type Options struct {
	BaseURL      string
	ClientID     string
	ClientSecret string
	// PaymentToken is the Payment Authorization token (X-Payment-Authorization-Token) required to buy and void labels.
	PaymentToken string
	Paths        Paths
	MailClasses  []string // rated by Rate, e.g. USPS_GROUND_ADVANTAGE, PRIORITY_MAIL, PRIORITY_MAIL_EXPRESS
	PriceType    string   // COMMERCIAL or RETAIL
	ImageType    string   // PDF or ZPL203DPI
	HTTP         *http.Client
	MaxRetries   int
	Now          func() time.Time
}

type uspsImpl struct {
	opts   Options
	client carrierhttp.Client
	tokens carrierhttp.TokenSource
}

var _ port.Carrier = (*uspsImpl)(nil)

// New returns the USPS Carrier.
func New(o Options) port.Carrier {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.PriceType == "" {
		o.PriceType = "COMMERCIAL"
	}
	if o.ImageType == "" {
		o.ImageType = "PDF"
	}
	if len(o.MailClasses) == 0 {
		o.MailClasses = []string{"USPS_GROUND_ADVANTAGE", "PRIORITY_MAIL", "PRIORITY_MAIL_EXPRESS"}
	}
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	u := &uspsImpl{opts: o}
	tokenClient := carrierhttp.New(carrierhttp.Options{Carrier: domain.USPS, HTTP: o.HTTP, MaxRetries: o.MaxRetries, ParseError: parseError})
	u.tokens = carrierhttp.NewTokenSource(func(ctx context.Context) (string, time.Duration, error) {
		res, err := tokenClient.Do(ctx, func(ctx context.Context) (*http.Request, error) {
			return jsonRequest(ctx, http.MethodPost, o.BaseURL+o.Paths.Token, map[string]string{
				"grant_type": "client_credentials", "client_id": o.ClientID, "client_secret": o.ClientSecret,
			})
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
	u.client = carrierhttp.New(carrierhttp.Options{Carrier: domain.USPS, HTTP: o.HTTP, Auth: u.tokens, MaxRetries: o.MaxRetries, ParseError: parseError})
	return u
}

func (u *uspsImpl) Code() domain.CarrierCode { return domain.USPS }

func (u *uspsImpl) do(ctx context.Context, build func(ctx context.Context) (*http.Request, error)) (carrierhttp.Response, error) {
	res, err := u.client.Do(ctx, build)
	if err != nil {
		return carrierhttp.Response{}, fmt.Errorf("usps: %w", err)
	}
	return res, nil
}

// parseError reads {"apiVersion":…,"error":{"code","message","errors":[{"code","title","detail"}]}}.
func parseError(_ int, body []byte) (string, string) {
	var e struct {
		Error struct {
			Code    string `json:"code"`
			Message string `json:"message"`
			Errors  []struct {
				Code   string `json:"code"`
				Title  string `json:"title"`
				Detail string `json:"detail"`
			} `json:"errors"`
		} `json:"error"`
	}
	if json.Unmarshal(body, &e) != nil {
		return "", ""
	}
	code, msg := e.Error.Code, e.Error.Message
	if len(e.Error.Errors) > 0 {
		first := e.Error.Errors[0]
		if first.Code != "" {
			code = first.Code
		}
		if first.Detail != "" {
			msg = first.Detail
		} else if first.Title != "" {
			msg = first.Title
		}
	}
	return code, msg
}

var mailClassNames = map[string]string{
	"USPS_GROUND_ADVANTAGE": "USPS Ground Advantage",
	"PRIORITY_MAIL":         "Priority Mail",
	"PRIORITY_MAIL_EXPRESS": "Priority Mail Express",
	"PARCEL_SELECT":         "Parcel Select",
	"MEDIA_MAIL":            "Media Mail",
}

type baseRateRequest struct {
	OriginZIPCode                string  `json:"originZIPCode"`
	DestinationZIPCode           string  `json:"destinationZIPCode"`
	Weight                       float64 `json:"weight"`
	Length                       float64 `json:"length"`
	Width                        float64 `json:"width"`
	Height                       float64 `json:"height"`
	MailClass                    string  `json:"mailClass"`
	ProcessingCategory           string  `json:"processingCategory"`
	DestinationEntryFacilityType string  `json:"destinationEntryFacilityType"`
	RateIndicator                string  `json:"rateIndicator"`
	PriceType                    string  `json:"priceType"`
	MailingDate                  string  `json:"mailingDate"`
	AccountType                  string  `json:"accountType,omitempty"`
	AccountNumber                string  `json:"accountNumber,omitempty"`
}

type baseRateResponse struct {
	TotalBasePrice float64 `json:"totalBasePrice"`
	Rates          []struct {
		SKU         string  `json:"SKU"`
		Description string  `json:"description"`
		PriceType   string  `json:"priceType"`
		Price       float64 `json:"price"`
		MailClass   string  `json:"mailClass"`
		Zone        string  `json:"zone"`
	} `json:"rates"`
}

func (u *uspsImpl) mailingDate(s domain.Shipment) string {
	d := s.ShipDate
	if d.IsZero() {
		d = u.opts.Now()
	}
	return d.Format(time.DateOnly)
}

// Rate prices every configured mail class for each parcel (base-rates search is per piece and class) in parallel.
func (u *uspsImpl) Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error) {
	if s.International() {
		return nil, &domain.CarrierError{Carrier: domain.USPS, Message: "international rating is not enabled for USPS", Kind: domain.ErrUnsupported}
	}
	type result struct {
		quote domain.Quote
		err   error
	}
	results := make([]result, len(u.opts.MailClasses))
	var wg sync.WaitGroup
	for i, mc := range u.opts.MailClasses {
		wg.Go(func() {
			q, err := u.rateClass(ctx, s, mc)
			results[i] = result{q, err}
		})
	}
	wg.Wait()
	quotes := make([]domain.Quote, 0, len(results))
	var errs []error
	for _, r := range results {
		if r.err != nil {
			errs = append(errs, r.err)
			continue
		}
		quotes = append(quotes, r.quote)
	}
	if len(quotes) == 0 && len(errs) > 0 {
		return nil, errors.Join(errs...)
	}
	return quotes, nil
}

func (u *uspsImpl) rateClass(ctx context.Context, s domain.Shipment, mailClass string) (domain.Quote, error) {
	q := domain.Quote{Carrier: domain.USPS, Service: domain.ServiceCode(mailClass), ServiceName: mailClassNames[mailClass], Currency: "USD"}
	for _, p := range s.Parcels {
		body := baseRateRequest{
			OriginZIPCode: s.From.ZIP5(), DestinationZIPCode: s.To.ZIP5(),
			Weight: p.WeightLb, Length: p.LengthIn, Width: p.WidthIn, Height: p.HeightIn,
			MailClass: mailClass, ProcessingCategory: "MACHINABLE", DestinationEntryFacilityType: "NONE",
			RateIndicator: "SP", PriceType: u.opts.PriceType, MailingDate: u.mailingDate(s),
		}
		if s.Account.Negotiated && s.Account.Number != "" {
			body.AccountType, body.AccountNumber = "EPS", s.Account.Number
		}
		res, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
			return jsonRequest(ctx, http.MethodPost, u.opts.BaseURL+u.opts.Paths.Prices, body)
		})
		if err != nil {
			return domain.Quote{}, err
		}
		var out baseRateResponse
		if err := decode(res.Body, &out); err != nil {
			return domain.Quote{}, err
		}
		price := out.TotalBasePrice
		if price == 0 && len(out.Rates) > 0 {
			price = out.Rates[0].Price
		}
		if price <= 0 {
			return domain.Quote{}, rejected("NO_RATE", "no price returned for "+mailClass)
		}
		q.AmountMinor += domain.MinorFromFloat(price)
		q.Negotiated = body.AccountNumber != ""
	}
	q.ListAmountMinor = q.AmountMinor
	return q, nil
}

type uspsAddress struct {
	FirstName        string `json:"firstName,omitempty"`
	LastName         string `json:"lastName,omitempty"`
	Firm             string `json:"firm,omitempty"`
	StreetAddress    string `json:"streetAddress"`
	SecondaryAddress string `json:"secondaryAddress,omitempty"`
	City             string `json:"city"`
	State            string `json:"state"`
	ZIPCode          string `json:"ZIPCode"`
	ZIPPlus4         string `json:"ZIPPlus4,omitempty"`
	Phone            string `json:"phone,omitempty"`
	Email            string `json:"email,omitempty"`
}

func toUSPSAddress(a domain.Address) uspsAddress {
	first, last := carrierhttp.SplitName(a.Name)
	return uspsAddress{FirstName: first, LastName: last, Firm: a.Company, StreetAddress: a.Line1, SecondaryAddress: a.Line2,
		City: a.City, State: a.State, ZIPCode: a.ZIP5(), ZIPPlus4: a.ZIP4(), Phone: a.Phone, Email: a.Email}
}

type labelRequest struct {
	ImageInfo struct {
		ImageType     string `json:"imageType"`
		LabelType     string `json:"labelType"`
		ReceiptOption string `json:"receiptOption"`
	} `json:"imageInfo"`
	ToAddress          uspsAddress `json:"toAddress"`
	FromAddress        uspsAddress `json:"fromAddress"`
	PackageDescription struct {
		MailClass                    string  `json:"mailClass"`
		RateIndicator                string  `json:"rateIndicator"`
		WeightUOM                    string  `json:"weightUOM"`
		Weight                       float64 `json:"weight"`
		DimensionsUOM                string  `json:"dimensionsUOM"`
		Length                       float64 `json:"length"`
		Width                        float64 `json:"width"`
		Height                       float64 `json:"height"`
		ProcessingCategory           string  `json:"processingCategory"`
		MailingDate                  string  `json:"mailingDate"`
		DestinationEntryFacilityType string  `json:"destinationEntryFacilityType"`
		CustomerReference            []struct {
			ReferenceNumber string `json:"referenceNumber"`
		} `json:"customerReference,omitempty"`
	} `json:"packageDescription"`
}

// LabelMetadata is the labelMetadata part of a Labels v3 response.
type LabelMetadata struct {
	TrackingNumber     string  `json:"trackingNumber"`
	Postage            float64 `json:"postage"`
	RoutingInformation string  `json:"routingInformation"`
	Zone               string  `json:"zone"`
	Commitment         struct {
		Name                  string `json:"name"`
		ScheduleDeliveryDate  string `json:"scheduleDeliveryDate"`
		ScheduledDeliveryDate string `json:"scheduledDeliveryDate"`
	} `json:"commitment"`
}

// BuyLabel creates a label (one parcel per USPS label; the first parcel is used and multi-piece shipments
// are bought as one label per parcel by the caller).
func (u *uspsImpl) BuyLabel(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error) {
	if u.opts.PaymentToken == "" {
		return domain.Label{}, &domain.CarrierError{Carrier: domain.USPS, Message: "USPS_PAYMENT_TOKEN is not configured", Kind: domain.ErrCarrierAuth}
	}
	if len(s.Parcels) != 1 {
		return domain.Label{}, &domain.CarrierError{Carrier: domain.USPS, Message: "USPS labels are single-piece; send one parcel per label", Kind: domain.ErrInvalid}
	}
	p := s.Parcels[0]
	var body labelRequest
	body.ImageInfo.ImageType, body.ImageInfo.LabelType, body.ImageInfo.ReceiptOption = u.opts.ImageType, "4X6LABEL", "NONE"
	body.ToAddress, body.FromAddress = toUSPSAddress(s.To), toUSPSAddress(s.From)
	pd := &body.PackageDescription
	pd.MailClass, pd.RateIndicator, pd.ProcessingCategory, pd.DestinationEntryFacilityType = string(service), "SP", "MACHINABLE", "NONE"
	pd.WeightUOM, pd.Weight, pd.DimensionsUOM = "lb", p.WeightLb, "in"
	pd.Length, pd.Width, pd.Height = p.LengthIn, p.WidthIn, p.HeightIn
	pd.MailingDate = u.mailingDate(s)
	if s.Reference != "" {
		pd.CustomerReference = []struct {
			ReferenceNumber string `json:"referenceNumber"`
		}{{ReferenceNumber: s.Reference}}
	}
	res, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
		req, err := jsonRequest(ctx, http.MethodPost, u.opts.BaseURL+u.opts.Paths.Label, body)
		if err != nil {
			return nil, err
		}
		req.Header.Set("X-Payment-Authorization-Token", u.opts.PaymentToken)
		req.Header.Set("Accept", "application/vnd.usps.labels+json, multipart/mixed")
		return req, nil
	})
	if err != nil {
		return domain.Label{}, err
	}
	meta, image, err := ParseLabelResponse(res.Header.Get("Content-Type"), res.Body)
	if err != nil {
		return domain.Label{}, &domain.CarrierError{Carrier: domain.USPS, Message: err.Error(), Kind: domain.ErrCarrierUnavailable}
	}
	if meta.TrackingNumber == "" || len(image) == 0 {
		return domain.Label{}, rejected("NO_LABEL", "label response had no tracking number or image")
	}
	format := domain.FormatPDF
	if strings.HasPrefix(u.opts.ImageType, "ZPL") {
		format = domain.FormatZPL
	}
	return domain.Label{Carrier: domain.USPS, Service: service, TrackingNumber: meta.TrackingNumber,
		CostMinor: domain.MinorFromFloat(meta.Postage), Currency: "USD", Format: format, Data: image}, nil
}

// ParseLabelResponse reads a Labels v3 response: multipart/mixed (labelMetadata JSON part + labelImage part) or
// the JSON representation {"labelMetadata":{…},"labelImage":"<base64>"}.
func ParseLabelResponse(contentType string, body []byte) (LabelMetadata, []byte, error) {
	mediaType, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		mediaType = "application/json"
	}
	if !strings.HasPrefix(mediaType, "multipart/") {
		var out struct {
			LabelMetadata LabelMetadata `json:"labelMetadata"`
			LabelImage    string        `json:"labelImage"`
		}
		if err := json.Unmarshal(body, &out); err != nil {
			return LabelMetadata{}, nil, fmt.Errorf("decode label json: %w", err)
		}
		img, err := base64.StdEncoding.DecodeString(out.LabelImage)
		if err != nil {
			return LabelMetadata{}, nil, fmt.Errorf("decode label image: %w", err)
		}
		return out.LabelMetadata, img, nil
	}
	var meta LabelMetadata
	var image []byte
	mr := multipart.NewReader(bytes.NewReader(body), params["boundary"])
	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return LabelMetadata{}, nil, fmt.Errorf("read multipart: %w", err)
		}
		data, err := io.ReadAll(part)
		if err != nil {
			return LabelMetadata{}, nil, fmt.Errorf("read part: %w", err)
		}
		switch part.FormName() {
		case "labelMetadata":
			if err := json.Unmarshal(data, &meta); err != nil {
				return LabelMetadata{}, nil, fmt.Errorf("decode labelMetadata: %w", err)
			}
		case "labelImage":
			image = maybeBase64(data, part.Header.Get("Content-Transfer-Encoding"))
		}
	}
	return meta, image, nil
}

func maybeBase64(data []byte, cte string) []byte {
	trimmed := bytes.TrimSpace(data)
	if strings.EqualFold(cte, "base64") || !bytes.HasPrefix(trimmed, []byte("%PDF")) && !bytes.HasPrefix(trimmed, []byte("^XA")) {
		if dec, err := base64.StdEncoding.DecodeString(string(trimmed)); err == nil {
			return dec
		}
	}
	return data
}

// Void cancels an unused label (refund request) — allowed until USPS accepts the item.
func (u *uspsImpl) Void(ctx context.Context, trackingNumber string) error {
	_, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
		req, err := jsonRequest(ctx, http.MethodDelete,
			u.opts.BaseURL+carrierhttp.Path(u.opts.Paths.Void, map[string]string{"trackingNumber": trackingNumber}), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("X-Payment-Authorization-Token", u.opts.PaymentToken)
		return req, nil
	})
	return err
}

// TrackingResponse is the Tracking v3 response (expand=DETAIL).
type TrackingResponse struct {
	TrackingNumber string `json:"trackingNumber"`
	StatusCategory string `json:"statusCategory"`
	Status         string `json:"status"`
	TrackingEvents []struct {
		EventType      string `json:"eventType"`
		EventTimestamp string `json:"eventTimestamp"`
		GMTTimestamp   string `json:"GMTTimestamp"`
		GMTOffset      string `json:"GMTOffset"`
		EventCity      string `json:"eventCity"`
		EventState     string `json:"eventState"`
		EventZIP       string `json:"eventZIP"`
		EventCountry   string `json:"eventCountry"`
		EventCode      string `json:"eventCode"`
	} `json:"trackingEvents"`
}

// Track fetches the full scan history of a tracking number.
func (u *uspsImpl) Track(ctx context.Context, trackingNumber string) ([]domain.TrackingEvent, error) {
	res, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
		return jsonRequest(ctx, http.MethodGet,
			u.opts.BaseURL+carrierhttp.Path(u.opts.Paths.Track, map[string]string{"trackingNumber": trackingNumber})+"?expand=DETAIL", nil)
	})
	if err != nil {
		return nil, err
	}
	var out TrackingResponse
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	return Events(trackingNumber, out), nil
}

// Events normalises a tracking response. USPS lists newest first; sequence numbers count from the oldest.
func Events(trackingNumber string, r TrackingResponse) []domain.TrackingEvent {
	n := len(r.TrackingEvents)
	evs := make([]domain.TrackingEvent, 0, n)
	for i, e := range r.TrackingEvents {
		at := parseUSPSTime(e.GMTTimestamp, e.EventTimestamp, e.GMTOffset)
		loc := strings.TrimSpace(strings.Join(nonEmpty(e.EventCity, e.EventState, e.EventZIP), ", "))
		evs = append(evs, domain.TrackingEvent{
			Carrier: domain.USPS, TrackingNumber: trackingNumber,
			EventID:     domain.EventKey("usps", trackingNumber, e.EventCode, at.UTC().Format(time.RFC3339), e.EventZIP),
			Code:        e.EventCode,
			Description: e.EventType,
			State:       MapStatus(e.EventCode, e.EventType),
			OccurredAt:  at,
			Location:    loc,
			Sequence:    n - 1 - i,
		})
	}
	domain.SortEvents(evs)
	return evs
}

func nonEmpty(parts ...string) []string {
	out := parts[:0]
	for _, p := range parts {
		if strings.TrimSpace(p) != "" {
			out = append(out, p)
		}
	}
	return out
}

func parseUSPSTime(gmt, local, offset string) time.Time {
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.000Z", "2006-01-02T15:04:05Z"} {
		if t, err := time.Parse(layout, gmt); err == nil {
			return t.UTC()
		}
	}
	if t, err := time.Parse(time.RFC3339, local); err == nil {
		return t.UTC()
	}
	if offset != "" {
		if t, err := time.Parse("2006-01-02T15:04:05-07:00", local+offset); err == nil {
			return t.UTC()
		}
	}
	if t, err := time.Parse("2006-01-02T15:04:05", local); err == nil {
		return t.UTC()
	}
	return time.Time{}
}

// MapStatus maps a USPS event code (falling back to the event text) to our shipment state.
func MapStatus(code, text string) domain.ShipmentState {
	switch strings.ToUpper(code) {
	case "GX", "MA":
		return domain.StateLabelCreated
	case "03", "OA", "80", "81", "SF", "TM":
		return domain.StatePickedUp
	case "OF":
		return domain.StateOutForDelivery
	case "01", "17", "DX":
		return domain.StateDelivered
	case "02", "52", "53", "54", "55", "56", "16", "14":
		return domain.StateDeliveryFailed
	case "09", "29", "31", "RT":
		return domain.StateReturned
	case "04", "05", "21", "22", "23", "24", "25", "26", "27", "28", "44":
		return domain.StateException
	case "07", "10", "A1", "L1", "T1", "U1", "NT", "PC", "OD", "82":
		return domain.StateInTransit
	}
	t := strings.ToLower(text)
	switch {
	case strings.Contains(t, "out for delivery"):
		return domain.StateOutForDelivery
	case strings.Contains(t, "delivered"):
		return domain.StateDelivered
	case strings.Contains(t, "label created"), strings.Contains(t, "pre-shipment"):
		return domain.StateLabelCreated
	case strings.Contains(t, "accepted"), strings.Contains(t, "picked up"):
		return domain.StatePickedUp
	case strings.Contains(t, "return to sender"), strings.Contains(t, "returned"):
		return domain.StateReturned
	case strings.Contains(t, "notice left"), strings.Contains(t, "attempt"):
		return domain.StateDeliveryFailed
	case strings.Contains(t, "alert"), strings.Contains(t, "undeliverable"), strings.Contains(t, "refused"):
		return domain.StateException
	default:
		return domain.StateInTransit
	}
}

type pickupRequest struct {
	PickupDate    string `json:"pickupDate"`
	PickupAddress struct {
		FirstName string      `json:"firstName"`
		LastName  string      `json:"lastName"`
		Firm      string      `json:"firm,omitempty"`
		Address   uspsAddress `json:"address"`
		Contact   []struct {
			Email string `json:"email,omitempty"`
			Phone string `json:"phone,omitempty"`
		} `json:"contact"`
	} `json:"pickupAddress"`
	Packages []struct {
		PackageType  string `json:"packageType"`
		PackageCount int    `json:"packageCount"`
	} `json:"packages"`
	EstimatedWeight float64 `json:"estimatedWeight"`
	PickupLocation  struct {
		PackageLocation     string `json:"packageLocation"`
		SpecialInstructions string `json:"specialInstructions,omitempty"`
	} `json:"pickupLocation"`
}

// SchedulePickup books a free Carrier Pickup on the requested date.
func (u *uspsImpl) SchedulePickup(ctx context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	var body pickupRequest
	body.PickupDate = p.Date.Format(time.DateOnly)
	body.PickupAddress.FirstName, body.PickupAddress.LastName = carrierhttp.SplitName(p.Address.Name)
	body.PickupAddress.Firm = p.Address.Company
	addr := toUSPSAddress(p.Address)
	addr.FirstName, addr.LastName, addr.Firm, addr.Phone, addr.Email = "", "", "", "", ""
	body.PickupAddress.Address = addr
	body.PickupAddress.Contact = []struct {
		Email string `json:"email,omitempty"`
		Phone string `json:"phone,omitempty"`
	}{{Email: p.Address.Email, Phone: p.Address.Phone}}
	pkgType := "OTHER"
	if p.Service != "" {
		pkgType = string(p.Service)
	}
	body.Packages = []struct {
		PackageType  string `json:"packageType"`
		PackageCount int    `json:"packageCount"`
	}{{PackageType: pkgType, PackageCount: p.Packages}}
	body.EstimatedWeight = p.TotalWeightLb
	body.PickupLocation.PackageLocation = "OTHER"
	body.PickupLocation.SpecialInstructions = p.Location
	res, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
		return jsonRequest(ctx, http.MethodPost, u.opts.BaseURL+u.opts.Paths.Pickup, body)
	})
	if err != nil {
		return domain.Pickup{}, err
	}
	var out struct {
		ConfirmationNumber string `json:"confirmationNumber"`
		PickupDate         string `json:"pickupDate"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Pickup{}, err
	}
	date := p.Date
	if d, err := time.Parse(time.DateOnly, out.PickupDate); err == nil {
		date = d
	}
	return domain.Pickup{Carrier: domain.USPS, Confirmation: out.ConfirmationNumber, Date: date}, nil
}

// AddressResponse is the Addresses v3 response.
type AddressResponse struct {
	Firm    string `json:"firm"`
	Address struct {
		StreetAddress    string `json:"streetAddress"`
		SecondaryAddress string `json:"secondaryAddress"`
		City             string `json:"city"`
		State            string `json:"state"`
		ZIPCode          string `json:"ZIPCode"`
		ZIPPlus4         string `json:"ZIPPlus4"`
	} `json:"address"`
	AdditionalInfo struct {
		DPVConfirmation string `json:"DPVConfirmation"`
		Business        string `json:"business"`
		Vacant          string `json:"vacant"`
	} `json:"additionalInfo"`
	Corrections []struct {
		Code string `json:"code"`
		Text string `json:"text"`
	} `json:"corrections"`
}

// ValidateAddress standardises a US address (CASS/DPV).
func (u *uspsImpl) ValidateAddress(ctx context.Context, a domain.Address) (domain.AddressValidation, error) {
	q := url.Values{"streetAddress": {a.Line1}, "city": {a.City}, "state": {a.State}}
	if a.Line2 != "" {
		q.Set("secondaryAddress", a.Line2)
	}
	if z := a.ZIP5(); z != "" {
		q.Set("ZIPCode", z)
	}
	res, err := u.do(ctx, func(ctx context.Context) (*http.Request, error) {
		return jsonRequest(ctx, http.MethodGet, u.opts.BaseURL+u.opts.Paths.Address+"?"+q.Encode(), nil)
	})
	if errors.Is(err, domain.ErrCarrierRejected) || errors.Is(err, domain.ErrNotFound) {
		return domain.AddressValidation{Carrier: domain.USPS, Valid: false, Messages: []string{"USPS could not match this address."}}, nil
	}
	if err != nil {
		return domain.AddressValidation{}, err
	}
	var out AddressResponse
	if err := decode(res.Body, &out); err != nil {
		return domain.AddressValidation{}, err
	}
	return InterpretAddress(out), nil
}

// InterpretAddress maps DPV confirmation codes (Y, D, S, N) to a validation.
func InterpretAddress(r AddressResponse) domain.AddressValidation {
	v := domain.AddressValidation{Carrier: domain.USPS, Messages: []string{}}
	zip := r.Address.ZIPCode
	if r.Address.ZIPPlus4 != "" {
		zip += "-" + r.Address.ZIPPlus4
	}
	std := domain.Address{Company: r.Firm, Line1: r.Address.StreetAddress, Line2: r.Address.SecondaryAddress,
		City: r.Address.City, State: r.Address.State, PostalCode: zip, Country: "US"}
	v.Standardized = &std
	if r.AdditionalInfo.Business != "" {
		res := r.AdditionalInfo.Business == "N"
		v.Residential = &res
		std.Residential = res
	}
	switch r.AdditionalInfo.DPVConfirmation {
	case "Y":
		v.Valid = true
	case "D":
		v.Messages = append(v.Messages, "Missing apartment, suite or unit number.")
	case "S":
		v.Valid = true
		v.Messages = append(v.Messages, "The apartment or suite number could not be confirmed.")
	default:
		v.Messages = append(v.Messages, "USPS cannot deliver to this address.")
	}
	if r.AdditionalInfo.Vacant == "Y" {
		v.Messages = append(v.Messages, "USPS lists this address as vacant.")
	}
	for _, c := range r.Corrections {
		if c.Text != "" {
			v.Messages = append(v.Messages, c.Text)
		}
	}
	return v
}
