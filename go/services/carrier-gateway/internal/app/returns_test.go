package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// boxFreeCarrier is a fakeCarrier whose integration offers label-free QR drop-off.
type boxFreeCarrier struct{ *fakeCarrier }

var _ port.BoxFreeReturner = boxFreeCarrier{}

func (b boxFreeCarrier) BuyBoxFreeReturn(_ context.Context, s domain.Shipment, svc domain.ServiceCode) (domain.Label, error) {
	b.buyCalls.Add(1)
	return domain.Label{Carrier: b.code, Service: svc, TrackingNumber: "QR-" + s.Reference, CostMinor: 750, Currency: "USD",
		Format: domain.FormatPNG, Data: []byte("png"), QRPayload: "QR:" + s.Reference, DropOffNetwork: "UPS Store"}, nil
}

func returnServices(carriers app.Carriers, store *memStore) app.ReturnLabelService {
	rates := app.NewRateService(carriers, allAccounts(), &memCache{}, time.Second, time.Minute, quietLog)
	labels := app.NewLabelService(carriers, allAccounts(), store, &memObjects{}, domain.BillingPolicy{}, quietLog, time.Now)
	return app.NewReturnLabelService(carriers, rates, labels)
}

func rated(code domain.CarrierCode, svc domain.ServiceCode, amount int64, days int) *fakeCarrier {
	return &fakeCarrier{code: code, quotes: []domain.Quote{{Carrier: code, Service: svc, AmountMinor: amount, TransitDays: days,
		Currency: "USD"}}}
}

func TestReturnLabelBuysTheChosenRate(t *testing.T) {
	usps := rated(domain.USPS, "GROUND", 800, 5)
	ups := rated(domain.UPS, "03", 1200, 2)
	svc := returnServices(app.Carriers{domain.USPS: usps, domain.UPS: ups}, newMemStore())
	ctx := context.Background()
	cheap, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "rma-1", Caller: "svc", Shipment: shipment("RMA-1")})
	if err != nil || cheap.Carrier != domain.USPS || cheap.Kind != domain.KindReturn || cheap.Quote == nil ||
		cheap.RateChoice != domain.RateCheapest || cheap.Mode != domain.ReturnPrintedLabel {
		t.Fatalf("cheapest %+v %v", cheap, err)
	}
	fast, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "rma-2", Caller: "svc", Shipment: shipment("RMA-2"),
		RateChoice: domain.RateFastest})
	if err != nil || fast.Carrier != domain.UPS {
		t.Fatalf("fastest %+v %v", fast, err)
	}
	// Retry with the same key replays even after rates moved (the hash is over the return request).
	ups.quotes[0].AmountMinor = 100
	again, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "rma-1", Caller: "svc", Shipment: shipment("RMA-1")})
	if err != nil || again.ID != cheap.ID || usps.buyCalls.Load() != 1 {
		t.Fatalf("replay %+v %v calls=%d", again, err, usps.buyCalls.Load())
	}
	chosen, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "rma-3", Caller: "svc", Shipment: shipment("RMA-3"),
		RateChoice: domain.RateService, Carrier: domain.UPS, Service: "02"})
	if err != nil || chosen.Carrier != domain.UPS || chosen.Service != "02" || chosen.Quote != nil {
		t.Fatalf("explicit service %+v %v", chosen, err)
	}
	if _, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "rma-4", Shipment: shipment("RMA-4"),
		RateChoice: domain.RateService}); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("SERVICE without carrier: %v", err)
	}
	if _, err := svc.Buy(ctx, app.ReturnLabelRequest{Shipment: shipment("RMA-5")}); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("missing key: %v", err)
	}
}

func TestBoxFreeReturnIsUnsupportedPerCarrierUnlessAnAdapterOffersIt(t *testing.T) {
	usps := rated(domain.USPS, "GROUND", 800, 5)
	ups := rated(domain.UPS, "03", 1200, 2)
	svc := returnServices(app.Carriers{domain.USPS: usps, domain.UPS: ups}, newMemStore())
	ctx := context.Background()
	_, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "qr-1", Shipment: shipment("RMA-Q1"),
		Mode: domain.ReturnBoxFreeQR})
	var ue *app.UnsupportedError
	if !errors.As(err, &ue) || !errors.Is(err, domain.ErrUnsupported) || len(ue.Carriers) != 2 ||
		ue.Carriers[0].Carrier != domain.USPS || ue.Carriers[1].Carrier != domain.UPS {
		t.Fatalf("expected per-carrier unsupported error, got %v", err)
	}
	fallback, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "qr-2", Shipment: shipment("RMA-Q2"),
		Mode: domain.ReturnBoxFreeQR, FallbackToLabel: true})
	if err != nil || fallback.Mode != domain.ReturnPrintedLabel || len(fallback.BoxFreeUnsupported) != 2 || fallback.BoxFree {
		t.Fatalf("fallback to a printed label %+v %v", fallback, err)
	}

	qr := boxFreeCarrier{rated(domain.UPS, "03", 1200, 2)}
	svc = returnServices(app.Carriers{domain.USPS: usps, domain.UPS: qr}, newMemStore())
	got, err := svc.Buy(ctx, app.ReturnLabelRequest{IdempotencyKey: "qr-3", Shipment: shipment("RMA-Q3"),
		Mode: domain.ReturnBoxFreeQR})
	if err != nil || got.Carrier != domain.UPS || !got.BoxFree || got.QRPayload != "QR:RMA-Q3" ||
		got.DropOffNetwork != "UPS Store" || got.Format != domain.FormatPNG || got.Mode != domain.ReturnBoxFreeQR {
		t.Fatalf("box-free return %+v %v", got, err)
	}
}
