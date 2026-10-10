package ups_test

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/ups"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// RatedShipment as an array (several services) – UPS sends an object when only one service is returned.
const shopResponse = `{"RateResponse":{"Response":{"ResponseStatus":{"Code":"1","Description":"Success"}},"RatedShipment":[
 {"Service":{"Code":"03","Description":""},"BillingWeight":{"UnitOfMeasurement":{"Code":"LBS"},"Weight":"3.0"},
  "TotalCharges":{"CurrencyCode":"USD","MonetaryValue":"14.27"},
  "NegotiatedRateCharges":{"TotalCharge":{"CurrencyCode":"USD","MonetaryValue":"11.02"}},
  "GuaranteedDelivery":{"BusinessDaysInTransit":"3"}},
 {"Service":{"Code":"01"},"TotalCharges":{"CurrencyCode":"USD","MonetaryValue":"58.90"},
  "NegotiatedRateCharges":{"TotalCharge":{"CurrencyCode":"USD","MonetaryValue":"41.10"}},
  "GuaranteedDelivery":{"BusinessDaysInTransit":"1","DeliveryByTime":"10:30 A.M."}}]}}`

const trackResponse = `{"trackResponse":{"shipment":[{"inquiryNumber":"1Z12345E0205271688","package":[{"trackingNumber":"1Z12345E0205271688",
 "activity":[
  {"location":{"address":{"city":"Brooklyn","stateProvince":"NY","countryCode":"US"}},"status":{"type":"D","description":"DELIVERED","code":"KB","statusCode":"011"},
   "date":"20261013","time":"141500","gmtDate":"20261013","gmtTime":"18:15:00"},
  {"location":{"address":{"city":"Brooklyn","stateProvince":"NY","countryCode":"US"}},"status":{"type":"I","description":"Out For Delivery Today","code":"OT","statusCode":"021"},
   "date":"20261013","time":"071000","gmtDate":"20261013","gmtTime":"11:10:00"},
  {"location":{"address":{"city":"Houston","stateProvince":"TX","countryCode":"US"}},"status":{"type":"P","description":"Pickup Scan","code":"PU","statusCode":"038"},
   "date":"20261010","time":"170200","gmtDate":"20261010","gmtTime":"22:02:00"},
  {"location":{"address":{"countryCode":"US"}},"status":{"type":"M","description":"Shipper created a label, UPS has not received the package yet.","code":"MP","statusCode":"003"},
   "date":"20261010","time":"093000","gmtDate":"20261010","gmtTime":"14:30:00"}]}]}]}}`

type fake struct {
	srv        *httptest.Server
	tokenCalls atomic.Int32
	shopCalls  atomic.Int32
}

func newFake(t *testing.T, singleRated bool) *fake {
	f := &fake{}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /security/v1/oauth/token", func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		_ = r.ParseForm()
		if !ok || user != "ups-id" || pass != "ups-secret" || r.PostForm.Get("grant_type") != "client_credentials" ||
			r.Header.Get("x-merchant-id") != "A1B2C3" || r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"response":{"errors":[{"code":"250003","message":"Invalid Access License number"}]}}`))
			return
		}
		f.tokenCalls.Add(1)
		_, _ = w.Write([]byte(`{"token_type":"Bearer","issued_at":"1760090000000","client_id":"ups-id","access_token":"ups-token","expires_in":"14399","status":"approved"}`))
	})
	auth := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Authorization") != "Bearer ups-token" || r.Header.Get("transId") == "" || r.Header.Get("transactionSrc") != "afrotransact" {
			w.WriteHeader(http.StatusUnauthorized)
			return false
		}
		return true
	}
	mux.HandleFunc("POST /api/rating/v2409/Shop", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		if f.shopCalls.Add(1) == 1 {
			w.WriteHeader(http.StatusServiceUnavailable) // first attempt fails: adapter must retry
			return
		}
		var b struct {
			RateRequest struct {
				Request  map[string]any `json:"Request"`
				Shipment struct {
					Shipper               map[string]any    `json:"Shipper"`
					ShipTo                map[string]any    `json:"ShipTo"`
					Package               []map[string]any  `json:"Package"`
					ShipmentRatingOptions map[string]string `json:"ShipmentRatingOptions"`
				} `json:"Shipment"`
			} `json:"RateRequest"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		s := b.RateRequest.Shipment
		pkgWeight := s.Package[0]["PackageWeight"].(map[string]any)
		if b.RateRequest.Request["RequestOption"] != "Shop" || s.Shipper["ShipperNumber"] != "A1B2C3" ||
			s.ShipmentRatingOptions["NegotiatedRatesIndicator"] != "Y" || pkgWeight["Weight"] != "2.5" ||
			s.ShipTo["Address"].(map[string]any)["PostalCode"] != "11201" {
			t.Errorf("unexpected rate request %+v", b)
		}
		if singleRated {
			_, _ = w.Write([]byte(`{"RateResponse":{"RatedShipment":{"Service":{"Code":"03"},"TotalCharges":{"CurrencyCode":"USD","MonetaryValue":"14.27"}}}}`))
			return
		}
		_, _ = w.Write([]byte(shopResponse))
	})
	mux.HandleFunc("POST /api/shipments/v2409/ship", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b map[string]map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		sh := b["ShipmentRequest"]["Shipment"].(map[string]any)
		ls := b["ShipmentRequest"]["LabelSpecification"].(map[string]any)
		if sh["Service"].(map[string]any)["Code"] != "03" || ls["LabelImageFormat"].(map[string]any)["Code"] != "ZPL" ||
			sh["PaymentInformation"].(map[string]any)["ShipmentCharge"].([]any)[0].(map[string]any)["BillShipper"].(map[string]any)["AccountNumber"] != "A1B2C3" {
			t.Errorf("unexpected ship request %+v", sh)
		}
		zpl := base64.StdEncoding.EncodeToString([]byte("^XA^FO50,50^FD1Z12345E0205271688^FS^XZ"))
		_, _ = w.Write([]byte(`{"ShipmentResponse":{"Response":{"ResponseStatus":{"Code":"1"}},"ShipmentResults":{
			"ShipmentCharges":{"TotalCharges":{"CurrencyCode":"USD","MonetaryValue":"14.27"}},
			"NegotiatedRateCharges":{"TotalCharge":{"CurrencyCode":"USD","MonetaryValue":"11.02"}},
			"ShipmentIdentificationNumber":"1Z12345E0205271688",
			"PackageResults":{"TrackingNumber":"1Z12345E0205271688","ShippingLabel":{"ImageFormat":{"Code":"ZPL"},"GraphicImage":"` + zpl + `"}}}}}`))
	})
	mux.HandleFunc("DELETE /api/shipments/v2409/void/cancel/{id}", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		if r.PathValue("id") == "1ZALREADYPICKEDUP" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"response":{"errors":[{"code":"190117","message":"Void period has expired"}]}}`))
			return
		}
		_, _ = w.Write([]byte(`{"VoidShipmentResponse":{"Response":{"ResponseStatus":{"Code":"1"}},"SummaryResult":{"Status":{"Code":"1","Description":"Voided"}}}}`))
	})
	mux.HandleFunc("GET /api/track/v1/details/{tn}", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) || r.URL.Query().Get("locale") != "en_US" {
			return
		}
		_, _ = w.Write([]byte(trackResponse))
	})
	mux.HandleFunc("POST /api/track/v1/subscription/standard/package", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b struct {
			TrackingNumberList []string          `json:"trackingNumberList"`
			Destination        map[string]string `json:"destination"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.Destination["url"] != "https://api.afrotransact.com/carrier-gateway/v1/webhooks/ups" || b.Destination["credential"] != "hook-cred" ||
			b.Destination["credentialType"] != "Bearer" || len(b.TrackingNumberList) != 1 {
			t.Errorf("unexpected subscription %+v", b)
		}
		_, _ = w.Write([]byte(`{"validTrackingNumbers":["1Z12345E0205271688"],"invalidTrackingNumbers":[]}`))
	})
	mux.HandleFunc("POST /api/shipments/v2409/pickup/oncall", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b map[string]map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		di := b["PickupCreationRequest"]["PickupDateInfo"].(map[string]any)
		if di["PickupDate"] != "20261013" || di["ReadyTime"] != "0900" || di["CloseTime"] != "1700" {
			t.Errorf("pickup date info %+v", di)
		}
		_, _ = w.Write([]byte(`{"PickupCreationResponse":{"Response":{"ResponseStatus":{"Code":"1"}},"PRN":"2929602E9CP"}}`))
	})
	mux.HandleFunc("POST /api/addressvalidation/v2/3", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		_, _ = w.Write([]byte(`{"XAVResponse":{"Response":{"ResponseStatus":{"Code":"1"}},"ValidAddressIndicator":"",
			"AddressClassification":{"Code":"2","Description":"Residential"},
			"Candidate":{"AddressClassification":{"Code":"2"},"AddressKeyFormat":{"AddressLine":"123 COURT ST","PoliticalDivision2":"BROOKLYN",
			"PoliticalDivision1":"NY","PostcodePrimaryLow":"11201","PostcodeExtendedLow":"4508","CountryCode":"US"}}}}`))
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func client(f *fake) ups.Adapter {
	return ups.New(ups.Options{BaseURL: f.srv.URL, ClientID: "ups-id", ClientSecret: "ups-secret", AccountNumber: "A1B2C3",
		Paths: ups.DefaultPaths(), WebhookURL: "https://api.afrotransact.com/carrier-gateway/v1/webhooks/ups",
		WebhookCredential: "hook-cred", HTTP: f.srv.Client()})
}

func shipment() domain.Shipment {
	return domain.Shipment{Reference: "PKG-1", OriginNode: "fc_houston_tx",
		From:    domain.Address{Name: "AfroTransact", Company: "AfroTransact Retail", Phone: "7135550100", Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002"},
		To:      domain.Address{Name: "Ada Obi", Phone: "7185550100", Line1: "123 Court St", City: "Brooklyn", State: "NY", PostalCode: "11201", Residential: true},
		Parcels: []domain.Parcel{{WeightLb: 2.5, LengthIn: 10, WidthIn: 8, HeightIn: 4}},
		Account: domain.Account{Carrier: domain.UPS, Negotiated: true}}
}

func TestUPSAdapter(t *testing.T) {
	f := newFake(t, false)
	c := client(f)
	ctx := context.Background()

	quotes, err := c.Rate(ctx, shipment())
	if err != nil {
		t.Fatal(err)
	}
	if len(quotes) != 2 || quotes[0].Service != "03" || quotes[0].AmountMinor != 1102 || quotes[0].ListAmountMinor != 1427 ||
		!quotes[0].Negotiated || quotes[0].TransitDays != 3 || quotes[1].ServiceName != "UPS Next Day Air" {
		t.Fatalf("quotes %+v", quotes)
	}
	if f.shopCalls.Load() != 2 {
		t.Fatalf("503 must be retried, shop calls = %d", f.shopCalls.Load())
	}

	label, err := c.BuyLabel(ctx, shipment(), "03")
	if err != nil {
		t.Fatal(err)
	}
	if label.TrackingNumber != "1Z12345E0205271688" || label.CostMinor != 1102 || label.Format != domain.FormatZPL ||
		!strings.HasPrefix(string(label.Data), "^XA") {
		t.Fatalf("label %+v", label)
	}
	if err := c.Subscribe(ctx, []string{label.TrackingNumber}); err != nil {
		t.Fatal(err)
	}
	if err := c.Void(ctx, label.TrackingNumber); err != nil {
		t.Fatal(err)
	}
	if err := c.Void(ctx, "1ZALREADYPICKEDUP"); !errors.Is(err, domain.ErrCarrierRejected) || !strings.Contains(err.Error(), "190117") {
		t.Fatalf("void after pickup must be a permanent rejection with UPS code, got %v", err)
	}

	evs, err := c.Track(ctx, "1Z12345E0205271688")
	if err != nil {
		t.Fatal(err)
	}
	want := []domain.ShipmentState{domain.StateLabelCreated, domain.StatePickedUp, domain.StateOutForDelivery, domain.StateDelivered}
	for i, ev := range evs {
		if ev.State != want[i] {
			t.Fatalf("event %d %+v want %s", i, ev, want[i])
		}
	}
	if !evs[3].OccurredAt.Equal(time.Date(2026, 10, 13, 18, 15, 0, 0, time.UTC)) {
		t.Fatalf("delivered at %v", evs[3].OccurredAt)
	}

	p, err := c.SchedulePickup(ctx, domain.PickupRequest{Address: shipment().From, Date: time.Date(2026, 10, 13, 0, 0, 0, 0, time.UTC),
		ReadyTime: "09:00", CloseTime: "17:00", Packages: 3, TotalWeightLb: 12})
	if err != nil || p.Confirmation != "2929602E9CP" {
		t.Fatalf("pickup %+v %v", p, err)
	}

	v, err := c.ValidateAddress(ctx, shipment().To)
	if err != nil || !v.Valid || v.Residential == nil || !*v.Residential || v.Standardized.PostalCode != "11201-4508" {
		t.Fatalf("validation %+v %v", v, err)
	}
	if f.tokenCalls.Load() != 1 {
		t.Fatalf("token fetched %d times, want 1", f.tokenCalls.Load())
	}
}

func TestUPSSingleRatedShipmentObject(t *testing.T) {
	f := newFake(t, true)
	quotes, err := client(f).Rate(context.Background(), shipment())
	if err != nil || len(quotes) != 1 || quotes[0].AmountMinor != 1427 || quotes[0].Negotiated {
		t.Fatalf("quotes %+v err %v", quotes, err)
	}
}

func TestUPSWrongCredentials(t *testing.T) {
	f := newFake(t, false)
	c := ups.New(ups.Options{BaseURL: f.srv.URL, ClientID: "ups-id", ClientSecret: "bad", AccountNumber: "A1B2C3", Paths: ups.DefaultPaths(), HTTP: f.srv.Client()})
	_, err := c.Track(context.Background(), "1Z")
	if !errors.Is(err, domain.ErrCarrierAuth) || !strings.Contains(err.Error(), "250003") {
		t.Fatalf("got %v", err)
	}
}

func TestUPSTrackAlertWebhook(t *testing.T) {
	c := client(newFake(t, false))
	body := []byte(`{"trackingNumber":"1Z12345E0205271688","localActivityDate":"20261011","localActivityTime":"031000",
		"scheduledDeliveryDate":"20261013","actualDeliveryDate":"","actualDeliveryTime":"","gmtActivityDate":"20261011","gmtActivityTime":"081000",
		"activityLocation":{"city":"LOUISVILLE","stateProvince":"KY","postalCode":"40209","country":"US"},
		"activityStatus":{"type":"I","code":"AR","description":"Arrived at Facility"}}`)
	if err := c.VerifyWebhook(http.Header{"Credential": {"wrong"}}, body); !errors.Is(err, domain.ErrSignature) {
		t.Fatalf("wrong credential accepted: %v", err)
	}
	if err := c.VerifyWebhook(http.Header{}, body); !errors.Is(err, domain.ErrSignature) {
		t.Fatalf("missing credential accepted: %v", err)
	}
	h := http.Header{}
	h.Set("credential", "hook-cred")
	if err := c.VerifyWebhook(h, body); err != nil {
		t.Fatal(err)
	}
	evs, err := c.ParseWebhook(body)
	if err != nil || len(evs) != 1 || evs[0].State != domain.StateInTransit || evs[0].Location != "LOUISVILLE, KY, US" ||
		!evs[0].OccurredAt.Equal(time.Date(2026, 10, 11, 8, 10, 0, 0, time.UTC)) {
		t.Fatalf("events %+v err %v", evs, err)
	}
}

func TestUPSStatusMapping(t *testing.T) {
	cases := []struct {
		typ, code string
		want      domain.ShipmentState
	}{
		{"M", "003", domain.StateLabelCreated},
		{"P", "038", domain.StatePickedUp},
		{"I", "005", domain.StateInTransit},
		{"I", "021", domain.StateOutForDelivery},
		{"O", "", domain.StateOutForDelivery},
		{"D", "011", domain.StateDelivered},
		{"X", "022", domain.StateDeliveryFailed},
		{"X", "", domain.StateException},
		{"RS", "", domain.StateReturned},
		{"MV", "", ""},
	}
	for _, tc := range cases {
		if got := ups.MapStatus(tc.typ, tc.code); got != tc.want {
			t.Errorf("MapStatus(%s,%s)=%s want %s", tc.typ, tc.code, got, tc.want)
		}
	}
}
