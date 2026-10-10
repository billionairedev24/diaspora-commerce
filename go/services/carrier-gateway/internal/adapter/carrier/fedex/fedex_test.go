package fedex_test

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/fedex"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

const trackOutput = `{"completeTrackResults":[{"trackingNumber":"794843185271","trackResults":[{
 "trackingNumberInfo":{"trackingNumber":"794843185271","carrierCode":"FDXE"},
 "latestStatusDetail":{"code":"DL","derivedCode":"DL","statusByLocale":"Delivered","description":"Delivered"},
 "scanEvents":[
  {"date":"2026-10-12T10:41:00-04:00","eventType":"DL","eventDescription":"Delivered","derivedStatusCode":"DL","derivedStatus":"Delivered",
   "scanLocation":{"city":"NEWARK","stateOrProvinceCode":"NJ","postalCode":"07102","countryCode":"US"}},
  {"date":"2026-10-12T08:05:00-04:00","eventType":"OD","eventDescription":"On FedEx vehicle for delivery","derivedStatusCode":"IT",
   "scanLocation":{"city":"NEWARK","stateOrProvinceCode":"NJ","postalCode":"07114","countryCode":"US"}},
  {"date":"2026-10-11T02:12:00-05:00","eventType":"DP","eventDescription":"Departed FedEx hub","derivedStatusCode":"IT",
   "scanLocation":{"city":"MEMPHIS","stateOrProvinceCode":"TN","postalCode":"38118","countryCode":"US"}},
  {"date":"2026-10-10T17:30:00-05:00","eventType":"PU","eventDescription":"Picked up","derivedStatusCode":"PU",
   "scanLocation":{"city":"HOUSTON","stateOrProvinceCode":"TX","postalCode":"77002","countryCode":"US"}},
  {"date":"2026-10-10T09:00:00-05:00","eventType":"OC","eventDescription":"Shipment information sent to FedEx","derivedStatusCode":"IN",
   "scanLocation":{"countryCode":"US"}}]}]}]}`

type fake struct {
	srv        *httptest.Server
	tokenCalls atomic.Int32
}

func newFake(t *testing.T) *fake {
	f := &fake{}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" || r.PostForm.Get("grant_type") != "client_credentials" ||
			r.PostForm.Get("client_id") != "fx-id" || r.PostForm.Get("client_secret") != "fx-secret" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"transactionId":"t1","errors":[{"code":"NOT.AUTHORIZED.ERROR","message":"The given client credentials were not valid."}]}`))
			return
		}
		f.tokenCalls.Add(1)
		_, _ = w.Write([]byte(`{"access_token":"fx-token","token_type":"bearer","expires_in":3599,"scope":"CXS"}`))
	})
	auth := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Authorization") != "Bearer fx-token" || r.Header.Get("X-locale") != "en_US" || r.Header.Get("x-customer-transaction-id") == "" {
			w.WriteHeader(http.StatusUnauthorized)
			return false
		}
		return true
	}
	mux.HandleFunc("POST /rate/v1/rates/quotes", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b struct {
			AccountNumber     map[string]string `json:"accountNumber"`
			RequestedShipment struct {
				RateRequestType []string         `json:"rateRequestType"`
				Recipient       map[string]any   `json:"recipient"`
				Items           []map[string]any `json:"requestedPackageLineItems"`
			} `json:"requestedShipment"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.AccountNumber["value"] != "740561073" || strings.Join(b.RequestedShipment.RateRequestType, ",") != "ACCOUNT,LIST" ||
			b.RequestedShipment.Items[0]["weight"].(map[string]any)["units"] != "LB" ||
			b.RequestedShipment.Recipient["address"].(map[string]any)["residential"] != true {
			t.Errorf("unexpected rate request %+v", b)
		}
		_, _ = w.Write([]byte(`{"transactionId":"t2","output":{"rateReplyDetails":[
			{"serviceType":"FEDEX_GROUND","serviceName":"FedEx Ground","packagingType":"YOUR_PACKAGING",
			 "ratedShipmentDetails":[{"rateType":"ACCOUNT","totalNetCharge":10.48,"currency":"USD"},{"rateType":"LIST","totalNetCharge":15.11,"currency":"USD"}],
			 "operationalDetail":{"transitTime":"THREE_DAYS"}},
			{"serviceType":"PRIORITY_OVERNIGHT","serviceName":"FedEx Priority Overnight®",
			 "ratedShipmentDetails":[{"rateType":"ACCOUNT","totalNetCharge":48.2,"currency":"USD"},{"rateType":"LIST","totalNetCharge":71.6,"currency":"USD"}],
			 "commit":{"dateDetail":{"dayOfWeek":"TUE","dayFormat":"2026-10-13T10:30:00"}},"operationalDetail":{"transitTime":"ONE_DAY"}}]}}`))
	})
	mux.HandleFunc("POST /ship/v1/shipments", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b struct {
			LabelResponseOptions string                     `json:"labelResponseOptions"`
			RequestedShipment    map[string]json.RawMessage `json:"requestedShipment"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.LabelResponseOptions != "LABEL" || string(b.RequestedShipment["serviceType"]) != `"FEDEX_GROUND"` ||
			!strings.Contains(string(b.RequestedShipment["labelSpecification"]), `"imageType":"PDF"`) ||
			!strings.Contains(string(b.RequestedShipment["requestedPackageLineItems"]), `"PKG-9"`) {
			t.Errorf("unexpected ship request %+v", b)
		}
		pdf := base64.StdEncoding.EncodeToString([]byte("%PDF-1.4 fedex"))
		_, _ = w.Write([]byte(`{"transactionId":"t3","output":{"transactionShipments":[{"masterTrackingNumber":"794843185271","serviceType":"FEDEX_GROUND",
			"pieceResponses":[{"trackingNumber":"794843185271","netChargeAmount":10.48,"packageDocuments":[{"contentType":"LABEL","docType":"PDF","encodedLabel":"` + pdf + `"}]}],
			"completedShipmentDetail":{"shipmentRating":{"shipmentRateDetails":[{"rateType":"PAYOR_ACCOUNT_PACKAGE","totalNetCharge":10.48,"currency":"USD"}]}}}]}}`))
	})
	mux.HandleFunc("PUT /ship/v1/shipments/cancel", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["trackingNumber"] != "794843185271" || b["deletionControl"] != "DELETE_ALL_PACKAGES" {
			t.Errorf("cancel body %+v", b)
		}
		_, _ = w.Write([]byte(`{"transactionId":"t4","output":{"cancelledShipment":true,"message":"Shipment is successfully cancelled"}}`))
	})
	mux.HandleFunc("POST /track/v1/trackingnumbers", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b struct {
			IncludeDetailedScans bool `json:"includeDetailedScans"`
			TrackingInfo         []struct {
				TrackingNumberInfo map[string]string `json:"trackingNumberInfo"`
			} `json:"trackingInfo"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !b.IncludeDetailedScans || b.TrackingInfo[0].TrackingNumberInfo["trackingNumber"] == "" {
			t.Errorf("track body %+v", b)
		}
		if b.TrackingInfo[0].TrackingNumberInfo["trackingNumber"] == "000000000000" {
			_, _ = w.Write([]byte(`{"output":{"completeTrackResults":[{"trackingNumber":"000000000000","trackResults":[{"error":{"code":"TRACKING.TRACKINGNUMBER.NOTFOUND","message":"Tracking number cannot be found."}}]}]}}`))
			return
		}
		_, _ = w.Write([]byte(`{"transactionId":"t5","output":` + trackOutput + `}`))
	})
	mux.HandleFunc("POST /rate/v1/rates/fail", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"transactionId":"t6","errors":[{"code":"SERVICE.UNAVAILABLE.ERROR","message":"The service is currently unavailable"}]}`))
	})
	mux.HandleFunc("POST /pickup/v1/pickups", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		od := b["originDetail"].(map[string]any)
		if b["carrierCode"] != "FDXG" || od["readyDateTimestamp"] != "2026-10-13T10:00:00Z" || od["customerCloseTime"] != "17:00:00" || od["pickupDateType"] != "FUTURE_DAY" {
			t.Errorf("pickup body %+v", b)
		}
		_, _ = w.Write([]byte(`{"output":{"pickupConfirmationCode":"NQAA97","location":"COSA"}}`))
	})
	mux.HandleFunc("POST /address/v1/addresses/resolve", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		_, _ = w.Write([]byte(`{"output":{"resolvedAddresses":[{"streetLinesToken":["10 FED EX PKWY"],"city":"MEMPHIS","stateOrProvinceCode":"TN",
			"postalCode":"38115-1234","countryCode":"US","classification":"BUSINESS","attributes":{"Resolved":"true","DPV":"true",
			"SuiteRequiredButMissing":"false","InvalidSuiteNumber":"false"}}]}}`))
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func client(f *fake, paths fedex.Paths) fedex.Adapter {
	return fedex.New(fedex.Options{BaseURL: f.srv.URL, ClientID: "fx-id", ClientSecret: "fx-secret", AccountNumber: "740561073",
		Paths: paths, WebhookSecret: "fx-hook", HTTP: f.srv.Client(), MaxRetries: -1,
		Now: func() time.Time { return time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC) }})
}

func shipment() domain.Shipment {
	return domain.Shipment{Reference: "PKG-9", OriginNode: "fc_houston_tx",
		From:    domain.Address{Name: "AfroTransact", Phone: "7135550100", Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002"},
		To:      domain.Address{Name: "Kofi Mensah", Phone: "9735550100", Line1: "5 Broad St", City: "Newark", State: "NJ", PostalCode: "07102", Residential: true},
		Parcels: []domain.Parcel{{WeightLb: 4.2, LengthIn: 12.5, WidthIn: 9, HeightIn: 6}},
		Account: domain.Account{Carrier: domain.FedEx, Number: "740561073", Negotiated: true}}
}

func TestFedExAdapter(t *testing.T) {
	f := newFake(t)
	c := client(f, fedex.DefaultPaths())
	ctx := context.Background()

	quotes, err := c.Rate(ctx, shipment())
	if err != nil {
		t.Fatal(err)
	}
	if len(quotes) != 2 || quotes[0].AmountMinor != 1048 || quotes[0].ListAmountMinor != 1511 || !quotes[0].Negotiated || quotes[0].TransitDays != 3 ||
		quotes[1].EstimatedDelivery == nil || quotes[1].TransitDays != 1 {
		t.Fatalf("quotes %+v", quotes)
	}
	label, err := c.BuyLabel(ctx, shipment(), "FEDEX_GROUND")
	if err != nil {
		t.Fatal(err)
	}
	if label.TrackingNumber != "794843185271" || label.CostMinor != 1048 || string(label.Data) != "%PDF-1.4 fedex" {
		t.Fatalf("label %+v", label)
	}
	if err := c.Void(ctx, label.TrackingNumber); err != nil {
		t.Fatal(err)
	}
	evs, err := c.Track(ctx, "794843185271")
	if err != nil {
		t.Fatal(err)
	}
	want := []domain.ShipmentState{domain.StateLabelCreated, domain.StatePickedUp, domain.StateInTransit, domain.StateOutForDelivery, domain.StateDelivered}
	for i, ev := range evs {
		if ev.State != want[i] {
			t.Fatalf("event %d %+v want %s", i, ev, want[i])
		}
	}
	if !evs[4].OccurredAt.Equal(time.Date(2026, 10, 12, 14, 41, 0, 0, time.UTC)) {
		t.Fatalf("delivered at %v", evs[4].OccurredAt)
	}
	if _, err := c.Track(ctx, "000000000000"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("unknown number: %v", err)
	}
	p, err := c.SchedulePickup(ctx, domain.PickupRequest{Address: shipment().From, Date: time.Date(2026, 10, 13, 0, 0, 0, 0, time.UTC),
		ReadyTime: "10:00", CloseTime: "17:00", Packages: 2, TotalWeightLb: 9, Service: "FEDEX_GROUND"})
	if err != nil || p.Confirmation != "NQAA97" {
		t.Fatalf("pickup %+v %v", p, err)
	}
	v, err := c.ValidateAddress(ctx, domain.Address{Line1: "10 Fedex Pkwy", City: "Memphis", State: "TN", PostalCode: "38115"})
	if err != nil || !v.Valid || v.Residential == nil || *v.Residential || v.Standardized.Line1 != "10 FED EX PKWY" {
		t.Fatalf("validation %+v %v", v, err)
	}
	if f.tokenCalls.Load() != 1 {
		t.Fatalf("token fetched %d times", f.tokenCalls.Load())
	}
}

func TestFedExErrorBodyMapsToRejected(t *testing.T) {
	f := newFake(t)
	paths := fedex.DefaultPaths()
	paths.Rate = "/rate/v1/rates/fail"
	_, err := client(f, paths).Rate(context.Background(), shipment())
	var ce *domain.CarrierError
	if !errors.Is(err, domain.ErrCarrierRejected) || !errors.As(err, &ce) || ce.Code != "SERVICE.UNAVAILABLE.ERROR" {
		t.Fatalf("got %v", err)
	}
}

func TestFedExWebhookSignature(t *testing.T) {
	c := client(newFake(t), fedex.DefaultPaths())
	body := []byte(trackOutput)
	mac := hmac.New(sha256.New, []byte("fx-hook"))
	mac.Write(body)
	sum := mac.Sum(nil)

	valid := http.Header{}
	valid.Set("fdx-signature", base64.StdEncoding.EncodeToString(sum))
	if err := c.VerifyWebhook(valid, body); err != nil {
		t.Fatalf("valid base64 signature rejected: %v", err)
	}
	hexSig := http.Header{}
	hexSig.Set("fdx-signature", hex.EncodeToString(sum))
	if err := c.VerifyWebhook(hexSig, body); err != nil {
		t.Fatalf("valid hex signature rejected: %v", err)
	}
	tampered := append([]byte{}, body...)
	tampered[10] = 'X'
	if err := c.VerifyWebhook(valid, tampered); !errors.Is(err, domain.ErrSignature) {
		t.Fatal("tampered body accepted")
	}
	if err := c.VerifyWebhook(http.Header{}, body); !errors.Is(err, domain.ErrSignature) {
		t.Fatal("missing signature accepted")
	}
	evs, err := c.ParseWebhook(body)
	if err != nil || len(evs) != 5 {
		t.Fatalf("bare payload: %d events, %v", len(evs), err)
	}
	wrapped, err := c.ParseWebhook([]byte(`{"output":` + trackOutput + `}`))
	if err != nil || len(wrapped) != 5 || wrapped[0].EventID != evs[0].EventID {
		t.Fatalf("wrapped payload must parse to identical events: %v", err)
	}
}

func TestFedExStatusMapping(t *testing.T) {
	cases := map[string]domain.ShipmentState{
		"OC": domain.StateLabelCreated, "PU": domain.StatePickedUp, "AR": domain.StateInTransit, "DP": domain.StateInTransit,
		"OD": domain.StateOutForDelivery, "DL": domain.StateDelivered, "DE": domain.StateDeliveryFailed, "SE": domain.StateException,
		"RS": domain.StateReturned, "XX": "",
	}
	for code, want := range cases {
		if got := fedex.MapStatus(code); got != want {
			t.Errorf("MapStatus(%s)=%s want %s", code, got, want)
		}
	}
}
