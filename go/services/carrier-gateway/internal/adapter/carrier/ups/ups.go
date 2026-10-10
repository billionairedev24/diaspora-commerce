// Package ups implements port.Carrier with the UPS REST APIs (OAuth client credentials, Rating "Shop",
// Shipping ship/void, Track details, Track Alert subscriptions and push, Pickup, Address Validation XAV).
package ups

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Paths are the UPS endpoint paths; API versions (v2409 …) live here so upgrades are configuration.
type Paths struct {
	Token     string `yaml:"token"`
	Rate      string `yaml:"rate"`
	Ship      string `yaml:"ship"`
	Void      string `yaml:"void"`
	Track     string `yaml:"track"`
	Subscribe string `yaml:"subscribe"`
	Pickup    string `yaml:"pickup"`
	Address   string `yaml:"address"`
}

// DefaultPaths are the current production paths.
func DefaultPaths() Paths {
	return Paths{ // #nosec G101 -- endpoint paths, not credentials
		Token:     "/security/v1/oauth/token",
		Rate:      "/api/rating/v2409/Shop",
		Ship:      "/api/shipments/v2409/ship",
		Void:      "/api/shipments/v2409/void/cancel/{shipmentId}",
		Track:     "/api/track/v1/details/{trackingNumber}",
		Subscribe: "/api/track/v1/subscription/standard/package",
		Pickup:    "/api/shipments/v2409/pickup/oncall",
		Address:   "/api/addressvalidation/v2/3",
	}
}

// Options configure the UPS adapter.
type Options struct {
	BaseURL      string
	ClientID     string
	ClientSecret string
	// AccountNumber is the default shipper number (overridden by a per-FC account).
	AccountNumber string
	Paths         Paths
	// WebhookURL is our public Track Alert endpoint; WebhookCredential is echoed by UPS in the "credential" header.
	WebhookURL        string
	WebhookCredential string
	HTTP              *http.Client
	MaxRetries        int
	Now               func() time.Time
}

type upsImpl struct {
	opts   Options
	client carrierhttp.Client
}

// Adapter is the UPS carrier with Track Alert push support.
type Adapter interface {
	port.Carrier
	port.WebhookReceiver
	port.TrackingSubscriber
}

var _ Adapter = (*upsImpl)(nil)

// New returns the UPS adapter.
func New(o Options) Adapter {
	if o.Now == nil {
		o.Now = time.Now
	}
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	tokenClient := carrierhttp.New(carrierhttp.Options{Carrier: domain.UPS, HTTP: o.HTTP, MaxRetries: o.MaxRetries, ParseError: parseError})
	tokens := carrierhttp.NewTokenSource(func(ctx context.Context) (string, time.Duration, error) {
		res, err := tokenClient.Do(ctx, func(ctx context.Context) (*http.Request, error) {
			req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+o.Paths.Token,
				strings.NewReader(url.Values{"grant_type": {"client_credentials"}}.Encode()))
			if err != nil {
				return nil, fmt.Errorf("token request: %w", err)
			}
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			req.Header.Set("x-merchant-id", o.AccountNumber)
			req.SetBasicAuth(o.ClientID, o.ClientSecret)
			return req, nil
		})
		if err != nil {
			return "", 0, fmt.Errorf("oauth: %w", carrierhttp.AuthFailure(err))
		}
		var tok struct {
			AccessToken string              `json:"access_token"`
			ExpiresIn   carrierhttp.FlexInt `json:"expires_in"` // UPS sends a string, e.g. "14399"
			Status      string              `json:"status"`
		}
		if err := decode(res.Body, &tok); err != nil {
			return "", 0, err
		}
		return tok.AccessToken, time.Duration(tok.ExpiresIn) * time.Second, nil
	}, o.Now)
	return &upsImpl{opts: o, client: carrierhttp.New(carrierhttp.Options{
		Carrier: domain.UPS, HTTP: o.HTTP, Auth: tokens, MaxRetries: o.MaxRetries, ParseError: parseError,
	})}
}

func (u *upsImpl) Code() domain.CarrierCode { return domain.UPS }

// parseError reads {"response":{"errors":[{"code","message"}]}}.
func parseError(_ int, body []byte) (string, string) {
	var e struct {
		Response struct {
			Errors []struct {
				Code    string `json:"code"`
				Message string `json:"message"`
			} `json:"errors"`
		} `json:"response"`
	}
	if json.Unmarshal(body, &e) != nil || len(e.Response.Errors) == 0 {
		return "", ""
	}
	return e.Response.Errors[0].Code, e.Response.Errors[0].Message
}

func transID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (u *upsImpl) do(ctx context.Context, method, path string, body any) (carrierhttp.Response, error) {
	res, err := u.client.Do(ctx, func(ctx context.Context) (*http.Request, error) {
		req, err := jsonRequest(ctx, method, u.opts.BaseURL+path, body)
		if err != nil {
			return nil, err
		}
		req.Header.Set("transId", transID())
		req.Header.Set("transactionSrc", "afrotransact")
		return req, nil
	})
	if err != nil {
		return carrierhttp.Response{}, fmt.Errorf("ups %s: %w", path, err)
	}
	return res, nil
}

// ServiceNames maps UPS service codes to names.
var ServiceNames = map[string]string{
	"01": "UPS Next Day Air", "02": "UPS 2nd Day Air", "03": "UPS Ground", "12": "UPS 3 Day Select",
	"13": "UPS Next Day Air Saver", "14": "UPS Next Day Air Early", "59": "UPS 2nd Day Air A.M.",
	"07": "UPS Worldwide Express", "08": "UPS Worldwide Expedited", "11": "UPS Standard", "65": "UPS Worldwide Saver",
}

type code struct {
	Code        string `json:"Code"`
	Description string `json:"Description,omitempty"`
}

type upsAddress struct {
	AddressLine       []string `json:"AddressLine"`
	City              string   `json:"City"`
	StateProvinceCode string   `json:"StateProvinceCode,omitempty"`
	PostalCode        string   `json:"PostalCode"`
	CountryCode       string   `json:"CountryCode"`
	ResidentialAddr   *string  `json:"ResidentialAddressIndicator,omitempty"`
}

type party struct {
	Name          string     `json:"Name"`
	AttentionName string     `json:"AttentionName,omitempty"`
	ShipperNumber string     `json:"ShipperNumber,omitempty"`
	Phone         *phone     `json:"Phone,omitempty"`
	Address       upsAddress `json:"Address"`
	EMailAddress  string     `json:"EMailAddress,omitempty"`
}

type phone struct {
	Number string `json:"Number"`
}

func toParty(a domain.Address, shipperNumber string) party {
	lines := []string{a.Line1}
	if a.Line2 != "" {
		lines = append(lines, a.Line2)
	}
	name := a.Company
	if name == "" {
		name = a.Name
	}
	p := party{Name: name, AttentionName: a.Name, ShipperNumber: shipperNumber, EMailAddress: a.Email,
		Address: upsAddress{AddressLine: lines, City: a.City, StateProvinceCode: a.State, PostalCode: a.PostalCode, CountryCode: a.CountryOrUS()}}
	if a.Phone != "" {
		p.Phone = &phone{Number: a.Phone}
	}
	if a.Residential {
		empty := ""
		p.Address.ResidentialAddr = &empty
	}
	return p
}

type dims struct {
	UnitOfMeasurement code   `json:"UnitOfMeasurement"`
	Length            string `json:"Length"`
	Width             string `json:"Width"`
	Height            string `json:"Height"`
}

type weight struct {
	UnitOfMeasurement code   `json:"UnitOfMeasurement"`
	Weight            string `json:"Weight"`
}

type pkg struct {
	PackagingType *code  `json:"PackagingType,omitempty"` // Rating
	Packaging     *code  `json:"Packaging,omitempty"`     // Shipping
	Dimensions    dims   `json:"Dimensions"`
	PackageWeight weight `json:"PackageWeight"`
}

func packages(ps []domain.Parcel, forShip bool) []pkg {
	out := make([]pkg, 0, len(ps))
	for _, p := range ps {
		x := pkg{
			Dimensions:    dims{UnitOfMeasurement: code{Code: "IN"}, Length: carrierhttp.Dec(p.LengthIn), Width: carrierhttp.Dec(p.WidthIn), Height: carrierhttp.Dec(p.HeightIn)},
			PackageWeight: weight{UnitOfMeasurement: code{Code: "LBS"}, Weight: carrierhttp.Dec(p.WeightLb)},
		}
		if forShip {
			x.Packaging = &code{Code: "02"}
		} else {
			x.PackagingType = &code{Code: "02"}
		}
		out = append(out, x)
	}
	return out
}

func (u *upsImpl) account(s domain.Shipment) string {
	if s.Account.Number != "" {
		return s.Account.Number
	}
	return u.opts.AccountNumber
}

type rateShipment struct {
	Shipper               party              `json:"Shipper"`
	ShipTo                party              `json:"ShipTo"`
	ShipFrom              party              `json:"ShipFrom"`
	NumOfPieces           string             `json:"NumOfPieces"`
	Package               []pkg              `json:"Package"`
	ShipmentRatingOptions *map[string]string `json:"ShipmentRatingOptions,omitempty"`
	PaymentDetails        *paymentDetails    `json:"PaymentDetails,omitempty"`
}

type paymentDetails struct {
	ShipmentCharge []shipmentCharge `json:"ShipmentCharge"`
}

type shipmentCharge struct {
	Type        string `json:"Type"`
	BillShipper struct {
		AccountNumber string `json:"AccountNumber"`
	} `json:"BillShipper"`
}

func billShipper(account string) *paymentDetails {
	c := shipmentCharge{Type: "01"}
	c.BillShipper.AccountNumber = account
	return &paymentDetails{ShipmentCharge: []shipmentCharge{c}}
}

type charge struct {
	CurrencyCode  string `json:"CurrencyCode"`
	MonetaryValue string `json:"MonetaryValue"`
}

// RatedShipment is one service in a Shop response.
type RatedShipment struct {
	Service               code   `json:"Service"`
	TotalCharges          charge `json:"TotalCharges"`
	NegotiatedRateCharges *struct {
		TotalCharge charge `json:"TotalCharge"`
	} `json:"NegotiatedRateCharges"`
	GuaranteedDelivery *struct {
		BusinessDaysInTransit string `json:"BusinessDaysInTransit"`
	} `json:"GuaranteedDelivery"`
}

// Rate calls Rating with RequestOption "Shop" (all services in one call).
func (u *upsImpl) Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error) {
	acct := u.account(s)
	sh := rateShipment{Shipper: toParty(s.From, acct), ShipTo: toParty(s.To, ""), ShipFrom: toParty(s.From, ""),
		NumOfPieces: fmt.Sprint(len(s.Parcels)), Package: packages(s.Parcels, false), PaymentDetails: billShipper(acct)}
	if s.Account.Negotiated {
		sh.ShipmentRatingOptions = &map[string]string{"NegotiatedRatesIndicator": "Y"}
	}
	body := map[string]any{"RateRequest": map[string]any{
		"Request":  map[string]any{"RequestOption": "Shop", "TransactionReference": map[string]string{"CustomerContext": s.Reference}},
		"Shipment": sh,
	}}
	res, err := u.do(ctx, http.MethodPost, u.opts.Paths.Rate, body)
	if err != nil {
		return nil, err
	}
	var out struct {
		RateResponse struct {
			RatedShipment json.RawMessage `json:"RatedShipment"`
		} `json:"RateResponse"`
	}
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	rated, err := carrierhttp.OneOrMany[RatedShipment](out.RateResponse.RatedShipment)
	if err != nil {
		return nil, rejected("DECODE", err.Error())
	}
	return Quotes(rated)
}

// Quotes converts rated shipments, preferring negotiated charges when UPS returns them.
func Quotes(rated []RatedShipment) ([]domain.Quote, error) {
	quotes := make([]domain.Quote, 0, len(rated))
	for _, r := range rated {
		list, err := domain.ParseMinor(r.TotalCharges.MonetaryValue)
		if err != nil {
			return nil, rejected("AMOUNT", err.Error())
		}
		q := domain.Quote{Carrier: domain.UPS, Service: domain.ServiceCode(r.Service.Code), ServiceName: ServiceNames[r.Service.Code],
			AmountMinor: list, ListAmountMinor: list, Currency: r.TotalCharges.CurrencyCode}
		if q.ServiceName == "" {
			q.ServiceName = "UPS " + r.Service.Code
		}
		if r.NegotiatedRateCharges != nil && r.NegotiatedRateCharges.TotalCharge.MonetaryValue != "" {
			neg, err := domain.ParseMinor(r.NegotiatedRateCharges.TotalCharge.MonetaryValue)
			if err == nil && neg > 0 {
				q.AmountMinor, q.Negotiated = neg, true
			}
		}
		if r.GuaranteedDelivery != nil {
			_, _ = fmt.Sscan(r.GuaranteedDelivery.BusinessDaysInTransit, &q.TransitDays)
		}
		quotes = append(quotes, q)
	}
	return quotes, nil
}

type shipResults struct {
	ShipmentCharges struct {
		TotalCharges charge `json:"TotalCharges"`
	} `json:"ShipmentCharges"`
	NegotiatedRateCharges *struct {
		TotalCharge charge `json:"TotalCharge"`
	} `json:"NegotiatedRateCharges"`
	ShipmentIdentificationNumber string          `json:"ShipmentIdentificationNumber"`
	PackageResults               json.RawMessage `json:"PackageResults"`
}

type packageResult struct {
	TrackingNumber string `json:"TrackingNumber"`
	ShippingLabel  struct {
		ImageFormat  code   `json:"ImageFormat"`
		GraphicImage string `json:"GraphicImage"`
	} `json:"ShippingLabel"`
}

// BuyLabel ships with the requested service and returns the ZPL label of the first package.
func (u *upsImpl) BuyLabel(ctx context.Context, s domain.Shipment, service domain.ServiceCode) (domain.Label, error) {
	acct := u.account(s)
	shipment := map[string]any{
		"Description":        firstNonEmpty(s.Reference, "AfroTransact order"),
		"Shipper":            toParty(s.From, acct),
		"ShipTo":             toParty(s.To, ""),
		"ShipFrom":           toParty(s.From, ""),
		"PaymentInformation": billShipper(acct),
		"Service":            code{Code: string(service), Description: ServiceNames[string(service)]},
		"Package":            packages(s.Parcels, true),
	}
	if s.Account.Negotiated {
		shipment["ShipmentRatingOptions"] = map[string]string{"NegotiatedRatesIndicator": "Y"}
	}
	if s.Reference != "" {
		shipment["ReferenceNumber"] = map[string]string{"Value": s.Reference}
	}
	if s.International() && s.Customs != nil {
		shipment["ShipmentServiceOptions"] = map[string]any{"InternationalForms": internationalForms(s, u.opts.Now())}
	}
	body := map[string]any{"ShipmentRequest": map[string]any{
		"Request":  map[string]any{"RequestOption": "nonvalidate", "TransactionReference": map[string]string{"CustomerContext": s.Reference}},
		"Shipment": shipment,
		"LabelSpecification": map[string]any{
			"LabelImageFormat": code{Code: "ZPL"},
			"LabelStockSize":   map[string]string{"Height": "6", "Width": "4"},
		},
	}}
	res, err := u.do(ctx, http.MethodPost, u.opts.Paths.Ship, body)
	if err != nil {
		return domain.Label{}, err
	}
	var out struct {
		ShipmentResponse struct {
			ShipmentResults shipResults `json:"ShipmentResults"`
		} `json:"ShipmentResponse"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Label{}, err
	}
	return labelFrom(out.ShipmentResponse.ShipmentResults, service)
}

func labelFrom(r shipResults, service domain.ServiceCode) (domain.Label, error) {
	pkgs, err := carrierhttp.OneOrMany[packageResult](r.PackageResults)
	if err != nil || len(pkgs) == 0 {
		return domain.Label{}, rejected("NO_PACKAGE", "ship response had no package results")
	}
	img, err := base64.StdEncoding.DecodeString(pkgs[0].ShippingLabel.GraphicImage)
	if err != nil || len(img) == 0 {
		return domain.Label{}, rejected("NO_LABEL", "ship response had no label image")
	}
	total := r.ShipmentCharges.TotalCharges
	if r.NegotiatedRateCharges != nil && r.NegotiatedRateCharges.TotalCharge.MonetaryValue != "" {
		total = r.NegotiatedRateCharges.TotalCharge
	}
	cost, err := domain.ParseMinor(total.MonetaryValue)
	if err != nil {
		return domain.Label{}, rejected("AMOUNT", err.Error())
	}
	return domain.Label{Carrier: domain.UPS, Service: service, TrackingNumber: pkgs[0].TrackingNumber,
		CarrierShipmentID: r.ShipmentIdentificationNumber, CostMinor: cost, Currency: total.CurrencyCode,
		Format: domain.FormatZPL, Data: img}, nil
}

func internationalForms(s domain.Shipment, now time.Time) map[string]any {
	products := make([]map[string]any, 0, len(s.Customs.Lines))
	for _, l := range s.Customs.Lines {
		products = append(products, map[string]any{
			"Description":       []string{l.Description},
			"Unit":              map[string]any{"Number": fmt.Sprint(l.Quantity), "Value": domain.FormatMajor(l.UnitValueMinor), "UnitOfMeasurement": code{Code: "PCS"}},
			"CommodityCode":     l.HSCode,
			"OriginCountryCode": l.OriginCountry,
		})
	}
	date := s.ShipDate
	if date.IsZero() {
		date = now
	}
	return map[string]any{
		"FormType":        "01",
		"InvoiceNumber":   s.Customs.InvoiceNumber,
		"InvoiceDate":     date.Format("20060102"),
		"ReasonForExport": firstNonEmpty(strings.ToUpper(s.Customs.ExportReason), "SALE"),
		"CurrencyCode":    s.Customs.Currency,
		"TermsOfShipment": firstNonEmpty(s.Customs.Incoterm, "DAP"),
		"Product":         products,
	}
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if v != "" {
			return v
		}
	}
	return ""
}

// Void cancels a shipment that UPS has not yet picked up.
func (u *upsImpl) Void(ctx context.Context, trackingNumber string) error {
	res, err := u.do(ctx, http.MethodDelete, carrierhttp.Path(u.opts.Paths.Void, map[string]string{"shipmentId": trackingNumber}), nil)
	if err != nil {
		return err
	}
	var out struct {
		VoidShipmentResponse struct {
			SummaryResult struct {
				Status code `json:"Status"`
			} `json:"SummaryResult"`
		} `json:"VoidShipmentResponse"`
	}
	if err := decode(res.Body, &out); err != nil {
		return err
	}
	if st := out.VoidShipmentResponse.SummaryResult.Status; st.Code != "1" {
		return rejected(st.Code, "void not confirmed: "+st.Description)
	}
	return nil
}

// TrackResponse is the Track details response.
type TrackResponse struct {
	TrackResponse struct {
		Shipment []struct {
			Package []struct {
				TrackingNumber string     `json:"trackingNumber"`
				Activity       []Activity `json:"activity"`
			} `json:"package"`
		} `json:"shipment"`
	} `json:"trackResponse"`
}

// Activity is one scan in Track details.
type Activity struct {
	Location struct {
		Address struct {
			City          string `json:"city"`
			StateProvince string `json:"stateProvince"`
			PostalCode    string `json:"postalCode"`
			CountryCode   string `json:"countryCode"`
		} `json:"address"`
	} `json:"location"`
	Status struct {
		Type        string `json:"type"`
		Description string `json:"description"`
		Code        string `json:"code"`
		StatusCode  string `json:"statusCode"`
	} `json:"status"`
	Date    string `json:"date"`    // YYYYMMDD local
	Time    string `json:"time"`    // HHMMSS local
	GMTDate string `json:"gmtDate"` // YYYYMMDD
	GMTTime string `json:"gmtTime"` // HH:MM:SS
}

// Track fetches activity for a tracking number.
func (u *upsImpl) Track(ctx context.Context, trackingNumber string) ([]domain.TrackingEvent, error) {
	path := carrierhttp.Path(u.opts.Paths.Track, map[string]string{"trackingNumber": trackingNumber}) + "?locale=en_US&returnSignature=false"
	res, err := u.do(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	var out TrackResponse
	if err := decode(res.Body, &out); err != nil {
		return nil, err
	}
	var evs []domain.TrackingEvent
	for _, sh := range out.TrackResponse.Shipment {
		for _, p := range sh.Package {
			tn := firstNonEmpty(p.TrackingNumber, trackingNumber)
			n := len(p.Activity)
			for i, a := range p.Activity {
				at := activityTime(a.GMTDate, a.GMTTime, a.Date, a.Time)
				addr := a.Location.Address
				evs = append(evs, domain.TrackingEvent{
					Carrier: domain.UPS, TrackingNumber: tn,
					EventID:     eventID(tn, at, a.Status.Type, a.Status.Code),
					Code:        a.Status.Type + "/" + a.Status.Code,
					Description: a.Status.Description,
					State:       MapStatus(a.Status.Type, a.Status.StatusCode),
					OccurredAt:  at,
					Location:    strings.Trim(strings.Join([]string{addr.City, addr.StateProvince, addr.CountryCode}, ", "), ", "),
					Sequence:    n - 1 - i,
				})
			}
		}
	}
	domain.SortEvents(evs)
	return evs, nil
}

// eventID is shared by Track and Track Alert so the same scan seen both ways de-duplicates.
func eventID(tn string, at time.Time, typ, code string) string {
	return domain.EventKey("ups", tn, at.UTC().Format(time.RFC3339), typ, code)
}

func activityTime(gmtDate, gmtTime, localDate, localTime string) time.Time {
	clean := func(s string) string { return strings.ReplaceAll(s, ":", "") }
	if gmtDate != "" {
		if t, err := time.Parse("20060102150405", gmtDate+padTime(clean(gmtTime))); err == nil {
			return t.UTC()
		}
	}
	if t, err := time.Parse("20060102150405", localDate+padTime(clean(localTime))); err == nil {
		return t.UTC()
	}
	return time.Time{}
}

func padTime(s string) string {
	for len(s) < 6 {
		s += "0"
	}
	return s[:6]
}

// MapStatus maps a UPS activity status type ("I", "D", "X", "P", "M", "O", "RS"…) and the numeric statusCode
// to our state. Status codes refine the type (021 out for delivery, 022–024 failed attempts).
func MapStatus(typ, statusCode string) domain.ShipmentState {
	switch statusCode {
	case "003":
		return domain.StateLabelCreated
	case "038":
		return domain.StatePickedUp
	case "021":
		return domain.StateOutForDelivery
	case "011", "026":
		return domain.StateDelivered
	case "022", "023", "024":
		return domain.StateDeliveryFailed
	case "013":
		return domain.StateException
	}
	switch strings.ToUpper(typ) {
	case "M":
		return domain.StateLabelCreated
	case "P":
		return domain.StatePickedUp
	case "I", "W", "DO", "DD":
		return domain.StateInTransit
	case "O":
		return domain.StateOutForDelivery
	case "D":
		return domain.StateDelivered
	case "X":
		return domain.StateException
	case "RS":
		return domain.StateReturned
	default: // MV (billing voided), NA — informational only
		return ""
	}
}

// Subscribe registers tracking numbers with Track Alert so UPS pushes their activity to our webhook.
func (u *upsImpl) Subscribe(ctx context.Context, trackingNumbers []string) error {
	if u.opts.WebhookURL == "" || len(trackingNumbers) == 0 {
		return nil
	}
	body := map[string]any{
		"locale": "en_US", "countryCode": "US", "trackingNumberList": trackingNumbers,
		"destination": map[string]string{"url": u.opts.WebhookURL, "credentialType": "Bearer", "credential": u.opts.WebhookCredential},
	}
	res, err := u.do(ctx, http.MethodPost, u.opts.Paths.Subscribe, body)
	if err != nil {
		return err
	}
	var out struct {
		ValidTrackingNumbers   []string `json:"validTrackingNumbers"`
		InvalidTrackingNumbers []string `json:"invalidTrackingNumbers"`
	}
	if err := decode(res.Body, &out); err != nil {
		return err
	}
	if len(out.InvalidTrackingNumbers) > 0 {
		return rejected("INVALID_TRACKING", "Track Alert rejected "+strings.Join(out.InvalidTrackingNumbers, ","))
	}
	return nil
}

// VerifyWebhook checks the credential UPS echoes back in the "credential" header (Track Alert offers no HMAC).
func (u *upsImpl) VerifyWebhook(h http.Header, _ []byte) error {
	if !carrierhttp.EqualSecret(u.opts.WebhookCredential, h.Get("credential")) {
		return domain.ErrSignature
	}
	return nil
}

// AlertEvent is a Track Alert push.
type AlertEvent struct {
	TrackingNumber    string `json:"trackingNumber"`
	LocalActivityDate string `json:"localActivityDate"`
	LocalActivityTime string `json:"localActivityTime"`
	GMTActivityDate   string `json:"gmtActivityDate"`
	GMTActivityTime   string `json:"gmtActivityTime"`
	ActivityLocation  struct {
		City          string `json:"city"`
		StateProvince string `json:"stateProvince"`
		PostalCode    string `json:"postalCode"`
		Country       string `json:"country"`
	} `json:"activityLocation"`
	ActivityStatus struct {
		Type        string `json:"type"`
		Code        string `json:"code"`
		Description string `json:"description"`
	} `json:"activityStatus"`
}

// ParseWebhook reads one Track Alert event (or an array of them).
func (u *upsImpl) ParseWebhook(body []byte) ([]domain.TrackingEvent, error) {
	alerts, err := carrierhttp.OneOrMany[AlertEvent](body)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", domain.ErrInvalid, err)
	}
	evs := make([]domain.TrackingEvent, 0, len(alerts))
	for _, a := range alerts {
		if a.TrackingNumber == "" {
			continue
		}
		at := activityTime(a.GMTActivityDate, a.GMTActivityTime, a.LocalActivityDate, a.LocalActivityTime)
		loc := a.ActivityLocation
		evs = append(evs, domain.TrackingEvent{
			Carrier: domain.UPS, TrackingNumber: a.TrackingNumber,
			EventID:     eventID(a.TrackingNumber, at, a.ActivityStatus.Type, a.ActivityStatus.Code),
			Code:        a.ActivityStatus.Type + "/" + a.ActivityStatus.Code,
			Description: a.ActivityStatus.Description,
			State:       MapStatus(a.ActivityStatus.Type, ""),
			OccurredAt:  at,
			Location:    strings.Trim(strings.Join([]string{loc.City, loc.StateProvince, loc.Country}, ", "), ", "),
		})
	}
	domain.SortEvents(evs)
	return evs, nil
}

// SchedulePickup books an on-call pickup and returns the PRN.
func (u *upsImpl) SchedulePickup(ctx context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	acct := firstNonEmpty(p.Account.Number, u.opts.AccountNumber)
	lines := p.Address.Line1
	if p.Address.Line2 != "" {
		lines += " " + p.Address.Line2
	}
	residential := "N"
	if p.Address.Residential {
		residential = "Y"
	}
	svc := firstNonEmpty(string(p.Service), "003")
	body := map[string]any{"PickupCreationRequest": map[string]any{
		"RatePickupIndicator": "N",
		"Shipper":             map[string]any{"Account": map[string]string{"AccountNumber": acct, "AccountCountryCode": p.Address.CountryOrUS()}},
		"PickupDateInfo": map[string]string{
			"CloseTime": strings.ReplaceAll(p.CloseTime, ":", ""), "ReadyTime": strings.ReplaceAll(p.ReadyTime, ":", ""),
			"PickupDate": p.Date.Format("20060102"),
		},
		"PickupAddress": map[string]any{
			"CompanyName": firstNonEmpty(p.Address.Company, p.Address.Name), "ContactName": p.Address.Name,
			"AddressLine": lines, "City": p.Address.City, "StateProvince": p.Address.State,
			"PostalCode": p.Address.PostalCode, "CountryCode": p.Address.CountryOrUS(),
			"ResidentialIndicator": residential, "Phone": map[string]string{"Number": p.Address.Phone},
			"PickupPoint": p.Location,
		},
		"AlternateAddressIndicator": "N",
		"PickupPiece": []map[string]string{{
			"ServiceCode": svc, "Quantity": fmt.Sprint(p.Packages), "DestinationCountryCode": "US", "ContainerCode": "01",
		}},
		"TotalWeight":         map[string]string{"Weight": carrierhttp.Dec(p.TotalWeightLb), "UnitOfMeasurement": "LBS"},
		"OverweightIndicator": "N",
		"PaymentMethod":       "01",
	}}
	res, err := u.do(ctx, http.MethodPost, u.opts.Paths.Pickup, body)
	if err != nil {
		return domain.Pickup{}, err
	}
	var out struct {
		PickupCreationResponse struct {
			PRN string `json:"PRN"`
		} `json:"PickupCreationResponse"`
	}
	if err := decode(res.Body, &out); err != nil {
		return domain.Pickup{}, err
	}
	if out.PickupCreationResponse.PRN == "" {
		return domain.Pickup{}, rejected("NO_PRN", "pickup response had no PRN")
	}
	return domain.Pickup{Carrier: domain.UPS, Confirmation: out.PickupCreationResponse.PRN, Date: p.Date}, nil
}

// XAVResponse is the Address Validation (request option 3: validation + classification) response.
type XAVResponse struct {
	XAVResponse struct {
		ValidAddressIndicator     *string         `json:"ValidAddressIndicator"`
		AmbiguousAddressIndicator *string         `json:"AmbiguousAddressIndicator"`
		NoCandidatesIndicator     *string         `json:"NoCandidatesIndicator"`
		AddressClassification     *code           `json:"AddressClassification"`
		Candidate                 json.RawMessage `json:"Candidate"`
	} `json:"XAVResponse"`
}

type xavCandidate struct {
	AddressClassification *code `json:"AddressClassification"`
	AddressKeyFormat      struct {
		AddressLine         json.RawMessage `json:"AddressLine"`
		PoliticalDivision2  string          `json:"PoliticalDivision2"`
		PoliticalDivision1  string          `json:"PoliticalDivision1"`
		PostcodePrimaryLow  string          `json:"PostcodePrimaryLow"`
		PostcodeExtendedLow string          `json:"PostcodeExtendedLow"`
		CountryCode         string          `json:"CountryCode"`
	} `json:"AddressKeyFormat"`
}

// ValidateAddress runs street-level validation with residential/commercial classification.
func (u *upsImpl) ValidateAddress(ctx context.Context, a domain.Address) (domain.AddressValidation, error) {
	lines := []string{a.Line1}
	if a.Line2 != "" {
		lines = append(lines, a.Line2)
	}
	body := map[string]any{"XAVRequest": map[string]any{"AddressKeyFormat": map[string]any{
		"AddressLine": lines, "PoliticalDivision2": a.City, "PoliticalDivision1": a.State,
		"PostcodePrimaryLow": a.ZIP5(), "CountryCode": a.CountryOrUS(),
	}}}
	res, err := u.do(ctx, http.MethodPost, u.opts.Paths.Address+"?regionalrequestindicator=false&maximumcandidatelistsize=3", body)
	if err != nil {
		return domain.AddressValidation{}, err
	}
	var out XAVResponse
	if err := decode(res.Body, &out); err != nil {
		return domain.AddressValidation{}, err
	}
	return InterpretXAV(out), nil
}

// InterpretXAV maps the indicator flags (present-but-empty keys) and classification to a validation.
func InterpretXAV(r XAVResponse) domain.AddressValidation {
	x := r.XAVResponse
	v := domain.AddressValidation{Carrier: domain.UPS, Messages: []string{}}
	switch {
	case x.NoCandidatesIndicator != nil:
		v.Messages = append(v.Messages, "UPS found no matching address.")
		return v
	case x.ValidAddressIndicator != nil:
		v.Valid = true
	case x.AmbiguousAddressIndicator != nil:
		v.Messages = append(v.Messages, "The address is ambiguous; pick one of the suggestions.")
	}
	cands, _ := carrierhttp.OneOrMany[xavCandidate](x.Candidate)
	if len(cands) > 0 {
		c := cands[0].AddressKeyFormat
		lines, _ := carrierhttp.OneOrMany[string](c.AddressLine)
		std := domain.Address{City: c.PoliticalDivision2, State: c.PoliticalDivision1, PostalCode: c.PostcodePrimaryLow, Country: c.CountryCode}
		if c.PostcodeExtendedLow != "" {
			std.PostalCode += "-" + c.PostcodeExtendedLow
		}
		if len(lines) > 0 {
			std.Line1 = lines[0]
		}
		if len(lines) > 1 {
			std.Line2 = lines[1]
		}
		v.Standardized = &std
	}
	if x.AddressClassification != nil && x.AddressClassification.Code != "0" {
		res := x.AddressClassification.Code == "2"
		v.Residential = &res
		if v.Standardized != nil {
			v.Standardized.Residential = res
		}
	}
	return v
}
