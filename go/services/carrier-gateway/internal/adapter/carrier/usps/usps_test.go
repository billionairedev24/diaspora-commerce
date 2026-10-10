package usps_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/usps"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

const trackSample = `{
  "trackingNumber": "9205590164917312751089",
  "statusCategory": "Delivered",
  "status": "Delivered, In/At Mailbox",
  "trackingEvents": [
    {"eventType": "Delivered, In/At Mailbox", "eventTimestamp": "2026-10-12T14:15:00", "GMTTimestamp": "2026-10-12T19:15:00.000Z",
     "GMTOffset": "-05:00", "eventCity": "NEW YORK", "eventState": "NY", "eventZIP": "10001", "eventCode": "01"},
    {"eventType": "Out for Delivery", "eventTimestamp": "2026-10-12T07:02:00", "GMTTimestamp": "2026-10-12T12:02:00.000Z",
     "eventCity": "NEW YORK", "eventState": "NY", "eventZIP": "10001", "eventCode": "OF"},
    {"eventType": "Arrived at USPS Regional Facility", "eventTimestamp": "2026-10-11T03:10:00", "GMTTimestamp": "2026-10-11T08:10:00.000Z",
     "eventCity": "JERSEY CITY", "eventState": "NJ", "eventZIP": "07097", "eventCode": "10"},
    {"eventType": "USPS in possession of item", "eventTimestamp": "2026-10-10T16:40:00", "GMTTimestamp": "2026-10-10T21:40:00.000Z",
     "eventCity": "HOUSTON", "eventState": "TX", "eventZIP": "77002", "eventCode": "03"},
    {"eventType": "Shipping Label Created, USPS Awaiting Item", "eventTimestamp": "2026-10-10T10:00:00", "GMTTimestamp": "2026-10-10T15:00:00.000Z",
     "eventCity": "HOUSTON", "eventState": "TX", "eventZIP": "77002", "eventCode": "GX"}
  ]
}`

type fakeUSPS struct {
	t          *testing.T
	tokenCalls atomic.Int32
	srv        *httptest.Server
}

func (f *fakeUSPS) bearer(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("Authorization") != "Bearer usps-token" {
		w.WriteHeader(http.StatusUnauthorized)
		return false
	}
	return true
}

func newFake(t *testing.T) *fakeUSPS {
	f := &fakeUSPS{t: t}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/v3/token", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["grant_type"] != "client_credentials" || body["client_id"] != "cid" || body["client_secret"] != "csecret" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"invalid_client"}`))
			return
		}
		f.tokenCalls.Add(1)
		_, _ = w.Write([]byte(`{"access_token":"usps-token","token_type":"Bearer","expires_in":28799,"issued_at":1760000000}`))
	})
	mux.HandleFunc("POST /prices/v3/base-rates/search", func(w http.ResponseWriter, r *http.Request) {
		if !f.bearer(w, r) {
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["originZIPCode"] != "77002" || b["destinationZIPCode"] != "10001" || b["weight"] != 2.5 || b["priceType"] != "COMMERCIAL" ||
			b["mailingDate"] != "2026-10-12" || b["processingCategory"] != "MACHINABLE" || b["rateIndicator"] != "SP" ||
			b["destinationEntryFacilityType"] != "NONE" || b["accountType"] != "EPS" || b["accountNumber"] != "1000123456" {
			t.Errorf("unexpected price request %v", b)
		}
		switch b["mailClass"] {
		case "PRIORITY_MAIL_EXPRESS":
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"apiVersion":"/prices/v3","error":{"code":"400","message":"Bad request",
				"errors":[{"status":"400","code":"010660002","title":"Rate not found","detail":"No rate for the given mail class"}]}}`))
		case "USPS_GROUND_ADVANTAGE":
			_, _ = w.Write([]byte(`{"totalBasePrice":9.35,"rates":[{"SKU":"DUXP0XXXXC07050","description":"USPS Ground Advantage Machinable Single-piece",
				"priceType":"COMMERCIAL","price":9.35,"weight":2.5,"dimWeight":0,"fees":[],"startDate":"2026-07-13","endDate":"","mailClass":"USPS_GROUND_ADVANTAGE","zone":"05"}]}`))
		default:
			_, _ = w.Write([]byte(`{"totalBasePrice":12.6,"rates":[{"SKU":"DPXX0XXXXC07050","price":12.6,"mailClass":"PRIORITY_MAIL","zone":"05"}]}`))
		}
	})
	mux.HandleFunc("POST /labels/v3/label", func(w http.ResponseWriter, r *http.Request) {
		if !f.bearer(w, r) {
			return
		}
		if r.Header.Get("X-Payment-Authorization-Token") != "pay-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		var b struct {
			ImageInfo          map[string]string `json:"imageInfo"`
			ToAddress          map[string]string `json:"toAddress"`
			FromAddress        map[string]string `json:"fromAddress"`
			PackageDescription map[string]any    `json:"packageDescription"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.ImageInfo["imageType"] != "PDF" || b.ImageInfo["labelType"] != "4X6LABEL" || b.ToAddress["ZIPCode"] != "10001" ||
			b.ToAddress["ZIPPlus4"] != "2233" || b.ToAddress["firstName"] != "Ada" || b.ToAddress["lastName"] != "Obi" ||
			b.PackageDescription["mailClass"] != "USPS_GROUND_ADVANTAGE" || b.PackageDescription["weightUOM"] != "lb" {
			t.Errorf("unexpected label request %+v", b)
		}
		var buf bytes.Buffer
		mw := multipart.NewWriter(&buf)
		meta, _ := mw.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="labelMetadata"`}, "Content-Type": {"application/json"}})
		_, _ = meta.Write([]byte(`{"labelAddress":{},"routingInformation":"420100019205590164917312751089","trackingNumber":"9205590164917312751089",
			"postage":9.35,"zone":"05","commitment":{"name":"3 Days","scheduleDeliveryDate":"2026-10-15"}}`))
		img, _ := mw.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="labelImage"`},
			"Content-Type": {"application/pdf"}, "Content-Transfer-Encoding": {"base64"}})
		_, _ = img.Write([]byte(base64.StdEncoding.EncodeToString([]byte("%PDF-1.7 label"))))
		_ = mw.Close()
		w.Header().Set("Content-Type", "multipart/mixed; boundary="+mw.Boundary())
		_, _ = w.Write(buf.Bytes())
	})
	mux.HandleFunc("DELETE /labels/v3/label/{tn}", func(w http.ResponseWriter, r *http.Request) {
		if !f.bearer(w, r) || r.Header.Get("X-Payment-Authorization-Token") != "pay-token" || r.PathValue("tn") != "9205590164917312751089" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`{"trackingNumber":"9205590164917312751089","status":"CANCELED"}`))
	})
	mux.HandleFunc("GET /tracking/v3/tracking/{tn}", func(w http.ResponseWriter, r *http.Request) {
		if !f.bearer(w, r) || r.URL.Query().Get("expand") != "DETAIL" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(trackSample))
	})
	mux.HandleFunc("GET /addresses/v3/address", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if !f.bearer(w, r) || q.Get("streetAddress") != "350 5th Ave" || q.Get("ZIPCode") != "10118" || q.Get("state") != "NY" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`{"firm":"","address":{"streetAddress":"350 5TH AVE","secondaryAddress":"","city":"NEW YORK","state":"NY",
			"ZIPCode":"10118","ZIPPlus4":"0110"},"additionalInfo":{"deliveryPoint":"99","carrierRoute":"C000","DPVConfirmation":"D",
			"DPVCMRA":"N","business":"Y","centralDeliveryPoint":"N","vacant":"N"},"corrections":[{"code":"32","text":"Default address: more information is needed"}],
			"matches":[{"code":"32","text":"Default"}]}`))
	})
	mux.HandleFunc("POST /pickup/v3/carrier-pickup", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !f.bearer(w, r) || b["pickupDate"] != "2026-10-13" || b["estimatedWeight"] != 30.0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`{"confirmationNumber":"WTC123456789","pickupDate":"2026-10-13"}`))
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func shipment() domain.Shipment {
	return domain.Shipment{
		OriginNode: "fc_houston_tx",
		From:       domain.Address{Name: "AfroTransact Houston", Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002", Country: "US"},
		To:         domain.Address{Name: "Ada Obi", Line1: "10 W 33rd St", City: "New York", State: "NY", PostalCode: "10001-2233", Country: "US"},
		Parcels:    []domain.Parcel{{WeightLb: 2.5, LengthIn: 10, WidthIn: 8, HeightIn: 4}},
		ShipDate:   time.Date(2026, 10, 12, 0, 0, 0, 0, time.UTC),
		Account:    domain.Account{Carrier: domain.USPS, Number: "1000123456", Negotiated: true},
	}
}

func TestUSPSAdapter(t *testing.T) {
	f := newFake(t)
	c := usps.New(usps.Options{BaseURL: f.srv.URL, ClientID: "cid", ClientSecret: "csecret", PaymentToken: "pay-token",
		Paths: usps.DefaultPaths(), HTTP: f.srv.Client()})
	ctx := context.Background()

	quotes, err := c.Rate(ctx, shipment())
	if err != nil {
		t.Fatal(err)
	}
	if len(quotes) != 2 {
		t.Fatalf("express is rejected, ground + priority remain: %+v", quotes)
	}
	byService := map[domain.ServiceCode]domain.Quote{}
	for _, q := range quotes {
		byService[q.Service] = q
	}
	if q := byService["USPS_GROUND_ADVANTAGE"]; q.AmountMinor != 935 || q.ServiceName != "USPS Ground Advantage" || !q.Negotiated {
		t.Fatalf("ground quote %+v", q)
	}

	label, err := c.BuyLabel(ctx, shipment(), "USPS_GROUND_ADVANTAGE")
	if err != nil {
		t.Fatal(err)
	}
	if label.TrackingNumber != "9205590164917312751089" || label.CostMinor != 935 || string(label.Data) != "%PDF-1.7 label" || label.Format != domain.FormatPDF {
		t.Fatalf("label %+v data=%q", label, label.Data)
	}
	if err := c.Void(ctx, label.TrackingNumber); err != nil {
		t.Fatal(err)
	}

	evs, err := c.Track(ctx, "9205590164917312751089")
	if err != nil {
		t.Fatal(err)
	}
	want := []domain.ShipmentState{domain.StateLabelCreated, domain.StatePickedUp, domain.StateInTransit, domain.StateOutForDelivery, domain.StateDelivered}
	if len(evs) != len(want) {
		t.Fatalf("events %+v", evs)
	}
	for i, ev := range evs {
		if ev.State != want[i] || ev.EventID == "" {
			t.Fatalf("event %d = %+v, want %s", i, ev, want[i])
		}
	}
	if !evs[4].OccurredAt.Equal(time.Date(2026, 10, 12, 19, 15, 0, 0, time.UTC)) || evs[4].Location != "NEW YORK, NY, 10001" {
		t.Fatalf("delivered event %+v", evs[4])
	}
	again, _ := c.Track(ctx, "9205590164917312751089")
	if again[4].EventID != evs[4].EventID {
		t.Fatal("event ids must be stable across polls (de-duplication)")
	}

	v, err := c.ValidateAddress(ctx, domain.Address{Line1: "350 5th Ave", City: "New York", State: "NY", PostalCode: "10118"})
	if err != nil {
		t.Fatal(err)
	}
	if v.Valid || v.Residential == nil || *v.Residential || v.Standardized.PostalCode != "10118-0110" || len(v.Messages) < 2 {
		t.Fatalf("validation %+v", v)
	}

	p, err := c.SchedulePickup(ctx, domain.PickupRequest{Address: shipment().From, Date: time.Date(2026, 10, 13, 0, 0, 0, 0, time.UTC),
		ReadyTime: "09:00", CloseTime: "17:00", Packages: 12, TotalWeightLb: 30})
	if err != nil || p.Confirmation != "WTC123456789" {
		t.Fatalf("pickup %+v %v", p, err)
	}
	if f.tokenCalls.Load() != 1 {
		t.Fatalf("OAuth token must be cached across calls, fetched %d times", f.tokenCalls.Load())
	}
}

func TestUSPSBadCredentialsAreAuthErrors(t *testing.T) {
	f := newFake(t)
	c := usps.New(usps.Options{BaseURL: f.srv.URL, ClientID: "cid", ClientSecret: "wrong", Paths: usps.DefaultPaths(), HTTP: f.srv.Client()})
	_, err := c.Track(context.Background(), "9205590164917312751089")
	if !errors.Is(err, domain.ErrCarrierAuth) || domain.Retryable(err) {
		t.Fatalf("got %v", err)
	}
}

func TestUSPSLabelJSONResponse(t *testing.T) {
	body := []byte(`{"labelMetadata":{"trackingNumber":"9400100000000000000000","postage":5.1},"labelImage":"` +
		base64.StdEncoding.EncodeToString([]byte("^XA^FDtest^XZ")) + `"}`)
	meta, img, err := usps.ParseLabelResponse("application/vnd.usps.labels+json", body)
	if err != nil || meta.TrackingNumber != "9400100000000000000000" || string(img) != "^XA^FDtest^XZ" {
		t.Fatalf("meta=%+v img=%q err=%v", meta, img, err)
	}
}

func TestUSPSStatusMapping(t *testing.T) {
	cases := map[[2]string]domain.ShipmentState{
		{"GX", ""}:                       domain.StateLabelCreated,
		{"03", ""}:                       domain.StatePickedUp,
		{"10", ""}:                       domain.StateInTransit,
		{"OF", ""}:                       domain.StateOutForDelivery,
		{"01", ""}:                       domain.StateDelivered,
		{"02", ""}:                       domain.StateDeliveryFailed,
		{"09", ""}:                       domain.StateReturned,
		{"04", ""}:                       domain.StateException,
		{"", "Out for Delivery"}:         domain.StateOutForDelivery,
		{"ZZ", "Departed USPS Facility"}: domain.StateInTransit,
	}
	for in, want := range cases {
		if got := usps.MapStatus(in[0], in[1]); got != want {
			t.Errorf("MapStatus(%q,%q) = %s, want %s", in[0], in[1], got, want)
		}
	}
}
