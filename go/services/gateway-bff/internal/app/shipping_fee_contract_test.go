package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// feeVectorsPath is shared with orders (ShippingFeeContractTest.java), the web mock and the customer app mock: the
// estimate the shopper sees and the amount orders charges are proven equal on the same numbers (listing rules D16).
const feeVectorsPath = "../../../../../tools/checkout/shipping-fee-vectors.json"

type feeVectors struct {
	Settings []struct {
		Name           string `json:"name"`
		FlatFeeMinor   int64  `json:"flatFeeMinor"`
		FreeAboveMinor *int64 `json:"freeAboveMinor"`
		Carts          []struct {
			ItemsMinor                int64 `json:"itemsMinor"`
			AfroTransactDeliveryMinor int64 `json:"afrotransactDeliveryMinor"`
			PickupMinor               int64 `json:"pickupMinor"`
		} `json:"carts"`
	} `json:"settings"`
}

func loadFeeVectors(t *testing.T) feeVectors {
	t.Helper()
	raw, err := os.ReadFile(feeVectorsPath)
	if err != nil {
		t.Fatal(err)
	}
	var v feeVectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	if len(v.Settings) < 4 {
		t.Fatalf("too few fee vectors: %d", len(v.Settings))
	}
	return v
}

func TestShippingFeeContractEstimateEqualsWhatOrdersCharges(t *testing.T) {
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	for _, set := range loadFeeVectors(t).Settings {
		e := domain.NewEstimator(domain.DeliveryPolicy{})
		e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 1,
			FlatFeeMinor: set.FlatFeeMinor, FreeAboveMinor: set.FreeAboveMinor})
		for _, c := range set.Carts {
			where := set.Name
			// Every package kind the shopper can see offers the same flat delivery fee and a free pickup.
			for _, kind := range []string{domain.FulfilledByAfroTransact, domain.AfroTransactDelivery, domain.ShipsFromSeller} {
				opts := e.Options(kind, 0, c.ItemsMinor, now)
				if len(opts) != 1 || opts[0].Key != domain.OptionDelivery || opts[0].FeeUnknown {
					t.Fatalf("%s %s: options = %+v", where, kind, opts)
				}
				if opts[0].FeeMinor != c.AfroTransactDeliveryMinor || opts[0].Free != (c.AfroTransactDeliveryMinor == 0) {
					t.Errorf("%s %s items %d: estimate %+v, orders charges %d", where, kind, c.ItemsMinor, opts[0], c.AfroTransactDeliveryMinor)
				}
				pkg := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "p:" + kind, Fulfilment: kind, SubtotalMinor: c.ItemsMinor, DeliveryOptions: opts}}
				addPickupOption(&pkg, []domain.PickupPoint{{ID: "pp-1", Ready: "Ready the same day"}})
				if got := packageShippingFee(pkg, map[string]string{pkg.PackageID: domain.OptionPickup}); got != c.PickupMinor {
					t.Errorf("%s %s items %d: pickup estimate %d, orders charges %d", where, kind, c.ItemsMinor, got, c.PickupMinor)
				}
				if got := packageShippingFee(pkg, map[string]string{pkg.PackageID: domain.OptionDelivery}); got != c.AfroTransactDeliveryMinor {
					t.Errorf("%s %s items %d: chosen delivery %d, orders charges %d", where, kind, c.ItemsMinor, got, c.AfroTransactDeliveryMinor)
				}
				if got := packageShippingFee(pkg, nil); got != c.AfroTransactDeliveryMinor {
					t.Errorf("%s %s items %d: default option %d, orders charges %d", where, kind, c.ItemsMinor, got, c.AfroTransactDeliveryMinor)
				}
			}
		}
	}
}

func TestNoExpressNoCarrierOptionWhileTheStaffSwitchIsOff(t *testing.T) {
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	e := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 3500})
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 1, FlatFeeMinor: 599})
	for _, kind := range []string{domain.FulfilledByAfroTransact, domain.AfroTransactDelivery, domain.ShipsFromSeller} {
		for _, o := range e.Options(kind, 0, 2000, now) {
			if o.Key == "express" || o.Key == domain.OptionCarrier {
				t.Errorf("%s offers %q with the seller carrier switch off", kind, o.Key)
			}
		}
	}
	// On: only a seller-shipped package gets the carrier option, still with AfroTransact delivery beside it.
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 2, FlatFeeMinor: 599, SellerCarrierShippingEnabled: true})
	seller := e.Options(domain.ShipsFromSeller, 0, 2000, now)
	if len(seller) != 2 || seller[0].Key != domain.OptionCarrier || seller[1].Key != domain.OptionDelivery {
		t.Fatalf("seller options with the switch on: %+v", seller)
	}
	if fc := e.Options(domain.FulfilledByAfroTransact, 0, 2000, now); len(fc) != 1 || fc[0].Key != domain.OptionDelivery {
		t.Fatalf("AfroTransact-fulfilled stays delivery only: %+v", fc)
	}
}

func TestAnUnreadFeeIsNeverShownAsZeroAndBlocksTheOrder(t *testing.T) {
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	opts := e.Options(domain.FulfilledByAfroTransact, 0, 2000, now)
	if len(opts) != 1 || !opts[0].FeeUnknown || opts[0].Free || opts[0].Label != domain.LabelFeeUnknown {
		t.Fatalf("an unread fee must be flagged, not $0: %+v", opts)
	}
	if p := e.Promise(domain.FulfilledByAfroTransact, 0, now); p.Free || p.FeeMinor != 0 {
		t.Fatalf("the promise must not claim free delivery: %+v", p)
	}
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Estimator: e}}
	sum := domain.CheckoutSummary{CanPlaceOrder: true, Packages: []domain.CheckoutPackage{{CartPackage: domain.CartPackage{PackageID: "p1", DeliveryOptions: opts}}}}
	s.blockUndeliverable(&sum, nil)
	if sum.CanPlaceOrder || sum.DeliveryBlockedReason != feeUnknownMessage {
		t.Fatalf("the order must wait for the fee: %+v", sum)
	}
	// A pickup choice has no fee to wait for.
	sum = domain.CheckoutSummary{CanPlaceOrder: true, Packages: []domain.CheckoutPackage{{CartPackage: domain.CartPackage{PackageID: "p1",
		DeliveryOptions: append(opts, domain.DeliveryOption{Key: domain.OptionPickup, Free: true})}}}}
	s.blockUndeliverable(&sum, map[string]string{"p1": domain.OptionPickup})
	if !sum.CanPlaceOrder {
		t.Fatalf("pickup needs no fee: %+v", sum)
	}
}

type zoneClient struct {
	port.DeliveryFactsClient
	zones []port.DeliveryZone
	err   error
}

func (z zoneClient) ZonesCovering(context.Context, float64, float64, string) ([]port.DeliveryZone, error) {
	return z.zones, z.err
}

func TestOutsideEveryDeliveryZoneTheOnlyDeliveryOptionIsQuoteRequired(t *testing.T) {
	lat, lng := 31.9, -102.1
	located := &port.IdentityAddress{Latitude: &lat, Longitude: &lng}
	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))

	if !outsideDeliveryArea(context.Background(), zoneClient{}, located, quiet) {
		t.Fatal("no zone covers the address: it is outside")
	}
	if outsideDeliveryArea(context.Background(), zoneClient{zones: []port.DeliveryZone{{Type: "DELIVERY", OwnerID: "anything", Name: "Any zone"}}}, located, quiet) {
		t.Fatal("inside any delivery zone the flat fee applies, whoever owns it")
	}
	if outsideDeliveryArea(context.Background(), zoneClient{err: errors.New("geo down")}, located, quiet) {
		t.Fatal("when zones cannot be read the shopper is not turned away")
	}
	if outsideDeliveryArea(context.Background(), zoneClient{}, &port.IdentityAddress{}, quiet) {
		t.Fatal("an address with no position cannot be judged: the flat fee applies")
	}

	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", FlatFeeMinor: 599})
	pkg := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "p1", Fulfilment: domain.FulfilledByAfroTransact, DeliveryOptions: e.Options(domain.FulfilledByAfroTransact, 0, 2000, now)}}
	addPickupOption(&pkg, []domain.PickupPoint{{ID: "pp-1", Ready: "Ready the same day"}})
	got := quoteRequiredOptions(pkg)
	if len(got) != 2 || got[0].Key != "quote_required" || got[0].Label != "Too far for standard delivery. Ask us for a quote" || got[0].FeeMinor != 0 || got[1].Key != "pickup" {
		t.Fatalf("options outside the zones: %+v", got)
	}
	pkg.DeliveryOptions = got
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Estimator: e}}
	sum := domain.CheckoutSummary{CanPlaceOrder: true, Packages: []domain.CheckoutPackage{pkg}}
	s.blockUndeliverable(&sum, nil)
	if sum.CanPlaceOrder || !strings.Contains(sum.DeliveryBlockedReason, "Ask us for a quote") {
		t.Fatalf("quote_required is disabled for payment: %+v", sum)
	}
	sum = domain.CheckoutSummary{CanPlaceOrder: true, Packages: []domain.CheckoutPackage{pkg}}
	s.blockUndeliverable(&sum, map[string]string{"p1": "pickup"})
	if !sum.CanPlaceOrder || packageShippingFee(pkg, map[string]string{"p1": "pickup"}) != 0 {
		t.Fatalf("pickup is always a way forward: %+v", sum)
	}
}

func TestAPreorderPackagePaysItsOwnWindowFeeBesideTheFlatFee(t *testing.T) {
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", FlatFeeMinor: 599})
	inStock := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_a:SELLER", Fulfilment: domain.ShipsFromSeller, SubtotalMinor: 2000,
		DeliveryOptions: e.Options(domain.ShipsFromSeller, 0, 2000, now)}}
	pre := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_b:SELLER:preorder", Fulfilment: domain.ShipsFromSeller, SubtotalMinor: 3000,
		Preorder: json.RawMessage(`{"windowId":"win-1","fulfilment":"DOOR","feeMinor":899}`), DeliveryOptions: []domain.DeliveryOption{}}}
	sum := domain.CheckoutSummary{Packages: []domain.CheckoutPackage{inStock, pre}}
	applyCheckoutShipping(&sum, nil)
	if sum.ShippingMinor != 599+899 {
		t.Fatalf("flat fee for the in-stock package and the window's own fee for the pre-order: %d", sum.ShippingMinor)
	}
	// Pickup in-stock does not touch the pre-order's fee, and nothing is charged twice.
	sum2 := domain.CheckoutSummary{Packages: []domain.CheckoutPackage{inStock, pre}}
	addPickupOption(&sum2.Packages[0], []domain.PickupPoint{{ID: "pp"}})
	applyCheckoutShipping(&sum2, map[string]string{"org_a:SELLER": "pickup"})
	if sum2.ShippingMinor != 899 {
		t.Fatalf("pickup for the in-stock package: %d", sum2.ShippingMinor)
	}
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Estimator: e}}
	sum.CanPlaceOrder = true
	s.blockUndeliverable(&sum, nil)
	if !sum.CanPlaceOrder {
		t.Fatalf("a pre-order is never blocked by the shipping change: %+v", sum)
	}
}

type fakeSettings struct {
	mu    sync.Mutex
	value domain.ShippingSettings
	err   error
	calls int
}

func (f *fakeSettings) Current(context.Context, string) (domain.ShippingSettings, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	return f.value, f.err
}

func (f *fakeSettings) fail(err error) {
	f.mu.Lock()
	f.err = err
	f.mu.Unlock()
}

func TestShippingSettingsSyncKeepsTheLastKnownFeeAndLogsWhenOrdersCannotBeRead(t *testing.T) {
	var logs strings.Builder
	log := slog.New(slog.NewTextHandler(&logs, nil))
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	set := e.(domain.ShippingSettingsSetter)
	reader := &fakeSettings{value: domain.ShippingSettings{Currency: "USD", Version: 3, FlatFeeMinor: 699}}
	sync := NewShippingSettingsSync(reader, set, "USD", time.Second, log)

	reader.fail(errors.New("orders down"))
	if err := sync.Refresh(context.Background()); err == nil {
		t.Fatal("a failed read is an error")
	}
	if _, ok := set.ShippingSettings(); ok {
		t.Fatal("nothing was ever read: the fee stays unknown, never a guess")
	}
	if !strings.Contains(logs.String(), "never read") || !strings.Contains(logs.String(), "level=ERROR") {
		t.Fatalf("the log must say plainly that no fee was ever read: %s", logs.String())
	}

	reader.fail(nil)
	if err := sync.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got, ok := set.ShippingSettings(); !ok || got.FlatFeeMinor != 699 || got.Version != 3 {
		t.Fatalf("after a good read: %+v", got)
	}

	logs.Reset()
	reader.fail(errors.New("orders down again"))
	if err := sync.Refresh(context.Background()); err == nil {
		t.Fatal("a failed read is an error")
	}
	if got, _ := set.ShippingSettings(); got.FlatFeeMinor != 699 {
		t.Fatalf("the last known value stays: %+v", got)
	}
	if !strings.Contains(logs.String(), "keeping the last known delivery fee") || !strings.Contains(logs.String(), "lastKnownFlatFeeMinor=699") {
		t.Fatalf("the log must name the value in use: %s", logs.String())
	}
	opts := e.Options(domain.FulfilledByAfroTransact, 0, 2000, time.Now())
	if opts[0].FeeUnknown || opts[0].FeeMinor != 699 {
		t.Fatalf("estimates keep using the last known fee: %+v", opts[0])
	}
}

func TestEnsureReadsOnceWhenNothingWasEverReadAndBacksOff(t *testing.T) {
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	reader := &fakeSettings{err: errors.New("orders down")}
	s := NewShippingSettingsSync(reader, e.(domain.ShippingSettingsSetter), "USD", time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
	for range 5 {
		s.Ensure(context.Background())
	}
	if reader.calls != 1 {
		t.Fatalf("requests must not hammer orders while it is down: %d reads", reader.calls)
	}
	reader.fail(nil)
	reader.mu.Lock()
	reader.value = domain.ShippingSettings{Currency: "USD", Version: 1, FlatFeeMinor: 599}
	reader.mu.Unlock()
	impl := s.(*shippingSettingsSyncImpl)
	impl.now = func() time.Time { return time.Now().Add(time.Minute) }
	s.Ensure(context.Background())
	if got, ok := e.(domain.ShippingSettingsSetter).ShippingSettings(); !ok || got.FlatFeeMinor != 599 {
		t.Fatalf("Ensure reads once the backoff passed: %+v %v", got, ok)
	}
	s.Ensure(context.Background())
	if reader.calls != 2 {
		t.Fatalf("no read once a value is known: %d", reader.calls)
	}
}
