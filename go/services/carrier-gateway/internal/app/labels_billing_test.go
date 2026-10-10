package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

var billing = domain.BillingPolicy{RetailOrgIDs: []string{"org_retail"}, PlatformNodes: []string{"fc_houston_tx"},
	HandlingPct: 10, HandlingFixedMinor: 25}

func sellerShipment(org, ref string) domain.Shipment {
	s := shipment(ref)
	s.OriginNode = org
	return s
}

// publishAll relays the outbox through the dispatcher and returns what reached the broker.
func publishAll(t *testing.T, store *memStore) []events.Envelope {
	t.Helper()
	pub := &recordingPublisher{}
	if _, err := app.NewDispatcher(store, pub, 100, time.Second, quietLog).RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	return pub.got
}

func labelEventOf(t *testing.T, e events.Envelope) domain.LabelEvent {
	t.Helper()
	var le domain.LabelEvent
	if err := json.Unmarshal(e.Payload, &le); err != nil {
		t.Fatal(err)
	}
	return le
}

func TestSellerLabelPurchaseAndVoidArePublished(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS}
	store := newMemStore()
	svc := app.NewLabelService(app.Carriers{domain.UPS: ups}, allAccounts(), store, &memObjects{}, billing, quietLog, time.Now)
	ctx := context.Background()
	req := app.BuyRequest{IdempotencyKey: "k1", Caller: "org_s", Shipment: sellerShipment("org_s", "shp-1"), Carrier: domain.UPS,
		Service: "03", Payer: domain.LabelPayer{OrgID: "org_s"}, OrderID: "ord-1"}
	v, err := svc.Buy(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	// Carrier cost 999 (negotiated charge) + 10 % (99.9 → 100) + 25 fixed handling.
	if v.BillTo != domain.BillSeller || v.BillingReason != domain.BillingSellerShipped || v.HandlingMinor != 125 || v.ChargeMinor != 1124 ||
		v.OrgID != "org_s" || v.ShipmentID != "shp-1" || v.Fulfilment != domain.FulfilmentSellerShipped {
		t.Fatalf("seller-shipped label is charged to the seller: %+v", v.LabelRecord)
	}
	if _, err := svc.Buy(ctx, req); err != nil {
		t.Fatal(err)
	}
	got := publishAll(t, store)
	if len(got) != 1 || got[0].Type != domain.EventLabelPurchased || got[0].Topic() != "at.carrier-shipment.events" ||
		got[0].AggregateID != "ups:"+v.TrackingNumber {
		t.Fatalf("one carrier.label.purchased per label (a replay publishes nothing): %+v", got)
	}
	e := labelEventOf(t, got[0])
	want := domain.LabelEvent{LabelID: v.ID, OrgID: "org_s", ShipmentID: "shp-1", OrderID: "ord-1", Reference: "shp-1", OriginNode: "org_s",
		Carrier: domain.UPS, Service: "03", TrackingNumber: v.TrackingNumber, Kind: domain.KindOutbound,
		Fulfilment: domain.FulfilmentSellerShipped, BillTo: domain.BillSeller, BillingReason: domain.BillingSellerShipped,
		CostMinor: 999, HandlingMinor: 125, AmountMinor: 1124, Currency: "USD", PurchasedAt: v.CreatedAt}
	if e != want {
		t.Fatalf("purchase payload\n got %+v\nwant %+v", e, want)
	}

	if _, err := svc.Void(ctx, v.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Void(ctx, v.ID); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("second void: %v", err)
	}
	got = publishAll(t, store)
	if len(got) != 1 || got[0].Type != domain.EventLabelVoided || got[0].AggregateID != "ups:"+v.TrackingNumber {
		t.Fatalf("one carrier.label.voided per label: %+v", got)
	}
	voided := labelEventOf(t, got[0])
	if voided.LabelID != v.ID || voided.AmountMinor != 1124 || voided.BillTo != domain.BillSeller || voided.VoidedAt == nil ||
		voided.VoidReason != domain.LabelVoidReasonVoided || voided.OrgID != "org_s" || voided.ShipmentID != "shp-1" {
		t.Fatalf("void payload %+v", voided)
	}
}

func TestLabelsAfroTransactPaysForArePublishedButNotSellerBilled(t *testing.T) {
	cases := []struct {
		name   string
		req    app.BuyRequest
		reason string
	}{
		{"AfroTransact Retail", app.BuyRequest{Shipment: sellerShipment("org_retail", "r-1"), Payer: domain.LabelPayer{OrgID: "org_retail"}},
			domain.BillingRetail},
		{"FBA shipment", app.BuyRequest{Shipment: sellerShipment("org_s", "f-1"), Payer: domain.LabelPayer{OrgID: "org_s"},
			Fulfilment: domain.FulfilmentFBA}, domain.BillingFBA},
		{"AfroTransact Delivery shipment", app.BuyRequest{Shipment: sellerShipment("org_s", "d-1"), Payer: domain.LabelPayer{OrgID: "org_s"},
			Fulfilment: domain.FulfilmentAfroTransactDelivery}, domain.BillingAfroTransactDelivery},
		{"fulfilment service at an FC", app.BuyRequest{Shipment: shipment("fc-1"), Payer: domain.LabelPayer{Platform: true}},
			domain.BillingPlatformCaller},
		{"seller labelling from an FC", app.BuyRequest{Shipment: shipment("fc-2"), Payer: domain.LabelPayer{OrgID: "org_s"}},
			domain.BillingPlatformOrigin},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			store := newMemStore()
			svc := app.NewLabelService(app.Carriers{domain.UPS: &fakeCarrier{code: domain.UPS}}, allAccounts(), store, &memObjects{},
				billing, quietLog, time.Now)
			req := c.req
			req.IdempotencyKey, req.Caller, req.Carrier, req.Service = "k", "c", domain.UPS, "03"
			v, err := svc.Buy(context.Background(), req)
			if err != nil {
				t.Fatal(err)
			}
			if v.BillTo != domain.BillAfroTransact || v.BillingReason != c.reason || v.HandlingMinor != 0 || v.ChargeMinor != 0 {
				t.Fatalf("not seller-billed: %+v", v.LabelRecord)
			}
			got := publishAll(t, store)
			if len(got) != 1 {
				t.Fatalf("events %+v", got)
			}
			if e := labelEventOf(t, got[0]); e.BillTo != domain.BillAfroTransact || e.BillingReason != c.reason || e.AmountMinor != 999 ||
				e.HandlingMinor != 0 {
				t.Fatalf("payload %+v", e)
			}
		})
	}
}
