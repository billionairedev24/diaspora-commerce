package dhl_test

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

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/dhl"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

const trackingSample = `{"shipments":[{"shipmentTrackingNumber":"1234567890","status":"Success","shipmentTimestamp":"2026-10-10T13:00:00",
 "productCode":"P","description":"Spices","events":[
  {"date":"2026-10-10","time":"18:02:00","GMTOffset":"-05:00","typeCode":"PU","description":"Shipment picked up",
   "serviceArea":[{"code":"IAH","description":"Houston-US"}]},
  {"date":"2026-10-11","time":"04:20:00","GMTOffset":"-04:00","typeCode":"DF","description":"Departed Facility in CINCINNATI HUB - USA",
   "serviceArea":[{"code":"CVG","description":"Cincinnati Hub-US"}]},
  {"date":"2026-10-12","time":"07:40:00","GMTOffset":"+01:00","typeCode":"CR","description":"Clearance processing complete at LONDON-HEATHROW - UK",
   "serviceArea":[{"code":"LHR","description":"London-Heathrow-GB"}]},
  {"date":"2026-10-12","time":"09:05:00","GMTOffset":"+01:00","typeCode":"WC","description":"With delivery courier",
   "serviceArea":[{"code":"LON","description":"London-GB"}]},
  {"date":"2026-10-12","time":"14:31:00","GMTOffset":"+01:00","typeCode":"OK","description":"Delivered","signedBy":"OBI",
   "serviceArea":[{"code":"LON","description":"London-GB"}]}]}]}`

type fake struct {
	srv   *httptest.Server
	calls atomic.Int32
}

func newFake(t *testing.T) *fake {
	f := &fake{}
	mux := http.NewServeMux()
	check := func(w http.ResponseWriter, r *http.Request) bool {
		f.calls.Add(1)
		user, pass, ok := r.BasicAuth()
		if !ok || user != "dhl-user" || pass != "dhl-pass" || len(r.Header.Get("Message-Reference")) < 28 || r.Header.Get("Message-Reference-Date") == "" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"instance":"/expressapi/rates","detail":"Invalid credentials","title":"Unauthorized","message":"Unauthorized","status":"401"}`))
			return false
		}
		return true
	}
	mux.HandleFunc("POST /rates", func(w http.ResponseWriter, r *http.Request) {
		if !check(w, r) {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		accts := b["accounts"].([]any)[0].(map[string]any)
		amount := b["monetaryAmount"].([]any)[0].(map[string]any)
		if accts["number"] != "958123456" || b["isCustomsDeclarable"] != true || b["unitOfMeasurement"] != "imperial" ||
			b["plannedShippingDateAndTime"] != "2026-10-10T13:00:00 GMT+00:00" || amount["value"] != 56.0 || amount["currency"] != "USD" {
			t.Errorf("unexpected rate request %v", b)
		}
		_, _ = w.Write([]byte(`{"products":[{"productName":"EXPRESS WORLDWIDE","productCode":"P","localProductCode":"P","networkTypeCode":"TD",
			"totalPrice":[{"currencyType":"BILLC","priceCurrency":"USD","price":86.42},{"currencyType":"PULCL","priceCurrency":"USD","price":86.42}],
			"deliveryCapabilities":{"deliveryTypeCode":"QDDC","estimatedDeliveryDateAndTime":"2026-10-13T23:59:00","totalTransitDays":"3"}}]}`))
	})
	mux.HandleFunc("POST /shipments", func(w http.ResponseWriter, r *http.Request) {
		if !check(w, r) {
			return
		}
		var b struct {
			ProductCode string `json:"productCode"`
			Content     struct {
				IsCustomsDeclarable   bool    `json:"isCustomsDeclarable"`
				DeclaredValue         float64 `json:"declaredValue"`
				DeclaredValueCurrency string  `json:"declaredValueCurrency"`
				Incoterm              string  `json:"incoterm"`
				ExportDeclaration     struct {
					LineItems []struct {
						Number         int                 `json:"number"`
						Description    string              `json:"description"`
						Price          float64             `json:"price"`
						Quantity       struct{ Value int } `json:"quantity"`
						CommodityCodes []struct {
							TypeCode string `json:"typeCode"`
							Value    string `json:"value"`
						} `json:"commodityCodes"`
						ManufacturerCountry string `json:"manufacturerCountry"`
					} `json:"lineItems"`
					Invoice map[string]string `json:"invoice"`
				} `json:"exportDeclaration"`
			} `json:"content"`
			CustomerDetails struct {
				ReceiverDetails struct {
					PostalAddress map[string]string `json:"postalAddress"`
				} `json:"receiverDetails"`
			} `json:"customerDetails"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		li := b.Content.ExportDeclaration.LineItems
		if b.ProductCode != "P" || !b.Content.IsCustomsDeclarable || b.Content.DeclaredValue != 56 || b.Content.Incoterm != "DAP" ||
			len(li) != 2 || li[0].CommodityCodes[0].Value != "2103.90" || li[0].Quantity.Value != 4 || li[0].Price != 9.5 ||
			li[1].ManufacturerCountry != "GH" || b.Content.ExportDeclaration.Invoice["number"] != "INV-77" ||
			b.CustomerDetails.ReceiverDetails.PostalAddress["countryCode"] != "GB" {
			t.Errorf("unexpected shipment request %+v", b)
		}
		pdf := base64.StdEncoding.EncodeToString([]byte("%PDF-1.5 dhl"))
		_, _ = w.Write([]byte(`{"shipmentTrackingNumber":"1234567890","trackingUrl":"https://express.api.dhl.com/mydhlapi/shipments/1234567890/tracking",
			"packages":[{"referenceNumber":1,"trackingNumber":"JD014600003828172345"}],
			"documents":[{"imageFormat":"PDF","content":"` + pdf + `","typeCode":"label"},{"imageFormat":"PDF","content":"` + pdf + `","typeCode":"invoice"}],
			"shipmentCharges":[{"currencyType":"BILLC","priceCurrency":"USD","price":86.42}]}`))
	})
	mux.HandleFunc("GET /shipments/{tn}/tracking", func(w http.ResponseWriter, r *http.Request) {
		if !check(w, r) || r.URL.Query().Get("trackingView") != "all-checkpoints" {
			return
		}
		_, _ = w.Write([]byte(trackingSample))
	})
	mux.HandleFunc("POST /pickups", func(w http.ResponseWriter, r *http.Request) {
		if !check(w, r) {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["plannedPickupDateAndTime"] != "2026-10-13T14:00:00 GMT-05:00" || b["closeTime"] != "18:00" || b["locationType"] != "business" {
			t.Errorf("pickup body %v", b)
		}
		_, _ = w.Write([]byte(`{"dispatchConfirmationNumbers":["PRG201220123456"],"readyByTime":"14:00","nextPickupDate":"2026-10-13"}`))
	})
	mux.HandleFunc("GET /address-validate", func(w http.ResponseWriter, r *http.Request) {
		if !check(w, r) {
			return
		}
		if r.URL.Query().Get("postalCode") == "00000" {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"title":"Not Found","detail":"996: No result found","status":"404"}`))
			return
		}
		_, _ = w.Write([]byte(`{"warnings":[],"address":[{"countryCode":"GB","postalCode":"SE1 9SG","cityName":"LONDON","serviceArea":{"code":"LON"}}]}`))
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func client(f *fake, password string) dhl.Adapter {
	return dhl.New(dhl.Options{BaseURL: f.srv.URL, Username: "dhl-user", Password: password, AccountNumber: "958123456",
		Paths: dhl.DefaultPaths(), WebhookSecret: "dhl-hook", HTTP: f.srv.Client(),
		Now: func() time.Time { return time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC) }})
}

func intlShipment() domain.Shipment {
	return domain.Shipment{Reference: "PKG-INT-1", OriginNode: "fc_houston_tx",
		From:    domain.Address{Name: "AfroTransact", Phone: "7135550100", Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002", Country: "US"},
		To:      domain.Address{Name: "Ada Obi", Phone: "+442071234567", Line1: "32 London Bridge St", City: "London", PostalCode: "SE1 9SG", Country: "GB"},
		Parcels: []domain.Parcel{{WeightLb: 3, LengthIn: 10, WidthIn: 8, HeightIn: 6}},
		Customs: &domain.Customs{Currency: "USD", InvoiceNumber: "INV-77", Lines: []domain.CustomsLine{
			{Description: "Suya spice mix", Quantity: 4, UnitValueMinor: 950, HSCode: "2103.90", OriginCountry: "NG", WeightLb: 1},
			{Description: "Shea butter", Quantity: 2, UnitValueMinor: 900, HSCode: "1515.90", OriginCountry: "GH", WeightLb: 1.5},
		}},
		ShipDate: time.Date(2026, 10, 10, 0, 0, 0, 0, time.UTC),
		Account:  domain.Account{Carrier: domain.DHL, Negotiated: true}}
}

func TestDHLAdapter(t *testing.T) {
	f := newFake(t)
	c := client(f, "dhl-pass")
	ctx := context.Background()
	s := intlShipment()
	if err := s.Validate(); err != nil {
		t.Fatal(err)
	}
	quotes, err := c.Rate(ctx, s)
	if err != nil {
		t.Fatal(err)
	}
	if len(quotes) != 1 || quotes[0].AmountMinor != 8642 || quotes[0].TransitDays != 3 || quotes[0].ServiceName != "DHL EXPRESS WORLDWIDE" {
		t.Fatalf("quotes %+v", quotes)
	}
	label, err := c.BuyLabel(ctx, s, "P")
	if err != nil {
		t.Fatal(err)
	}
	if label.TrackingNumber != "1234567890" || label.CostMinor != 8642 || string(label.Data) != "%PDF-1.5 dhl" || label.Format != domain.FormatPDF {
		t.Fatalf("label %+v", label)
	}
	before := f.calls.Load()
	if err := c.Void(ctx, label.TrackingNumber); err != nil || f.calls.Load() != before {
		t.Fatalf("DHL void needs no API call: err=%v", err)
	}
	evs, err := c.Track(ctx, "1234567890")
	if err != nil {
		t.Fatal(err)
	}
	want := []domain.ShipmentState{domain.StatePickedUp, domain.StateInTransit, domain.StateInTransit, domain.StateOutForDelivery, domain.StateDelivered}
	for i, ev := range evs {
		if ev.State != want[i] {
			t.Fatalf("event %d %+v want %s", i, ev, want[i])
		}
	}
	if !evs[4].OccurredAt.Equal(time.Date(2026, 10, 12, 13, 31, 0, 0, time.UTC)) || evs[4].Location != "London-GB" {
		t.Fatalf("delivered %+v", evs[4])
	}
	houston := time.FixedZone("CDT", -5*3600)
	p, err := c.SchedulePickup(ctx, domain.PickupRequest{Address: s.From, Date: time.Date(2026, 10, 13, 0, 0, 0, 0, houston),
		ReadyTime: "14:00", CloseTime: "18:00", Packages: 1, TotalWeightLb: 3, International: true})
	if err != nil || p.Confirmation != "PRG201220123456" {
		t.Fatalf("pickup %+v %v", p, err)
	}
	v, err := c.ValidateAddress(ctx, s.To)
	if err != nil || !v.Valid || v.Standardized.City != "LONDON" {
		t.Fatalf("validation %+v %v", v, err)
	}
	if bad, err := c.ValidateAddress(ctx, domain.Address{PostalCode: "00000", Country: "GB"}); err != nil || bad.Valid {
		t.Fatalf("unknown postcode must be an invalid result: %+v %v", bad, err)
	}
}

func TestDHLBasicAuthFailure(t *testing.T) {
	_, err := client(newFake(t), "wrong").Rate(context.Background(), intlShipment())
	if !errors.Is(err, domain.ErrCarrierAuth) || !strings.Contains(err.Error(), "Invalid credentials") {
		t.Fatalf("got %v", err)
	}
}

func TestDHLPushVerification(t *testing.T) {
	c := client(newFake(t), "dhl-pass")
	body := []byte(trackingSample)
	h := http.Header{}
	h.Set("X-DHL-Signature", carrierhttp.SignHMAC("dhl-hook", body))
	if err := c.VerifyWebhook(h, body); err != nil {
		t.Fatal(err)
	}
	h.Set("X-DHL-Signature", carrierhttp.SignHMAC("other", body))
	if err := c.VerifyWebhook(h, body); !errors.Is(err, domain.ErrSignature) {
		t.Fatal("wrong secret accepted")
	}
	evs, err := c.ParseWebhook(body)
	if err != nil || len(evs) != 5 {
		t.Fatalf("%d events %v", len(evs), err)
	}
}

func TestDHLStatusMapping(t *testing.T) {
	cases := map[string]domain.ShipmentState{
		"PU": domain.StatePickedUp, "PL": domain.StateInTransit, "DF": domain.StateInTransit, "AF": domain.StateInTransit,
		"WC": domain.StateOutForDelivery, "OK": domain.StateDelivered, "NH": domain.StateDeliveryFailed, "CA": domain.StateDeliveryFailed,
		"RT": domain.StateReturned, "OH": domain.StateException, "BA": domain.StateException, "ZZ": "",
	}
	for code, want := range cases {
		if got := dhl.MapStatus(code); got != want {
			t.Errorf("MapStatus(%s)=%s want %s", code, got, want)
		}
	}
}
