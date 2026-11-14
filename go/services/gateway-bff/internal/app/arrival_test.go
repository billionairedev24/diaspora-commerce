package app

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var arrivalNow = time.Date(2026, 11, 14, 9, 0, 0, 0, time.UTC) // a Saturday

// recordingPromise is the promise service: it records what it was asked and answers a window per offer.
type recordingPromise struct {
	asked  []port.PromiseOffer
	zip    string
	err    error
	answer func(port.PromiseOffer) port.PromiseQuote
}

func (p *recordingPromise) Promises(_ context.Context, zip string, offers []port.PromiseOffer) ([]port.PromiseQuote, error) {
	p.zip = zip
	p.asked = append(p.asked, offers...)
	if p.err != nil {
		return nil, p.err
	}
	var out []port.PromiseQuote
	for _, o := range offers {
		if p.answer != nil {
			out = append(out, p.answer(o))
			continue
		}
		out = append(out, port.PromiseQuote{OfferID: o.OfferID, Available: true, EarliestDate: "2026-11-18", LatestDate: "2026-11-20", CutoffLabel: "Order within 3 hrs"})
	}
	return out, nil
}

func sellerReq(key string) ArrivalRequest {
	return ArrivalRequest{Key: key, Fulfilment: domain.ShipsFromSeller, SellerOrgID: "org_laserfeet", HandlingDays: 2, HandlingKnown: true, ShipFromZip: "30303-1234"}
}

func TestASellerPackageIsDatedFromTheSellersHandlingTheirShipFromZipAndTheShoppersZip(t *testing.T) {
	p := &recordingPromise{}
	got := NewArrivalService(p, nil).Windows(context.Background(), "77042-1111", []ArrivalRequest{sellerReq("a")}, arrivalNow)
	a := got["a"]
	if !a.Known() || a.Label != "Nov 18 – 20" || a.EarliestDate != "2026-11-18" || a.LatestDate != "2026-11-20" || a.CutoffLabel == "" || a.Days != 4 {
		t.Fatalf("%+v", a)
	}
	if p.zip != "77042" || len(p.asked) != 1 {
		t.Fatalf("asked for %q: %+v", p.zip, p.asked)
	}
	o := p.asked[0]
	if o.Origin["type"] != "SELLER" || o.Origin["id"] != "org_laserfeet" || o.Origin["shipFromZip"] != "30303" || o.Origin["handlingDays"] != 2 ||
		o.Method["type"] != "CARRIER" || o.Method["serviceLevel"] != "GROUND" {
		t.Fatalf("the request carries the real inputs: %+v", o)
	}
}

func TestNoRealBasisMeansNoDateAndTheReasonNeverADefault(t *testing.T) {
	handlingUnknown := sellerReq("handling")
	handlingUnknown.HandlingKnown = false
	noShipFrom := sellerReq("shipfrom")
	noShipFrom.ShipFromZip = ""
	noOrg := sellerReq("org")
	noOrg.SellerOrgID = " "
	pickup := sellerReq("pickup")
	pickup.Option = domain.OptionPickup
	local := ArrivalRequest{Key: "local", Fulfilment: domain.AfroTransactDelivery, SellerOrgID: "org_x"}
	p := &recordingPromise{}
	got := NewArrivalService(p, nil).Windows(context.Background(), "77042", []ArrivalRequest{handlingUnknown, noShipFrom, noOrg, pickup, local}, arrivalNow)
	for _, k := range []string{"handling", "shipfrom", "org", "pickup", "local"} {
		if a := got[k]; a.Known() || a.Label != "" || a.Reason != domain.ArrivalNoOrigin || a.Basis != domain.ArrivalNone {
			t.Errorf("%s: %+v", k, a)
		}
	}
	if len(p.asked) != 0 {
		t.Fatalf("a request without a real origin is never sent: %+v", p.asked)
	}
	// no ZIP for the shopper at all
	if a := NewArrivalService(p, nil).Windows(context.Background(), "", []ArrivalRequest{sellerReq("x")}, arrivalNow)["x"]; a.Known() || a.Reason != domain.ArrivalNoPlace {
		t.Fatalf("no place: %+v", a)
	}
	if a := NewArrivalService(p, nil).Windows(context.Background(), "ABCDE", []ArrivalRequest{sellerReq("x")}, arrivalNow)["x"]; a.Known() || a.Reason != domain.ArrivalNoPlace {
		t.Fatalf("not a ZIP: %+v", a)
	}
	// no promise service at all
	if a := NewArrivalService(nil, nil).Windows(context.Background(), "77042", []ArrivalRequest{sellerReq("x")}, arrivalNow)["x"]; a.Known() || a.Reason != domain.ArrivalNoService {
		t.Fatalf("no service: %+v", a)
	}
}

func TestAPromiseTheServiceCannotMakeOrAServiceThatFailsGivesNoDate(t *testing.T) {
	down := &recordingPromise{err: errors.New("promise: down")}
	if a := NewArrivalService(down, nil).Windows(context.Background(), "77042", []ArrivalRequest{sellerReq("x")}, arrivalNow)["x"]; a.Known() || a.Reason != domain.ArrivalUnavailable {
		t.Fatalf("service error: %+v", a)
	}
	no := &recordingPromise{answer: func(o port.PromiseOffer) port.PromiseQuote {
		return port.PromiseQuote{OfferID: o.OfferID, Available: false}
	}}
	if a := NewArrivalService(no, nil).Windows(context.Background(), "99950", []ArrivalRequest{sellerReq("x")}, arrivalNow)["x"]; a.Known() || a.Reason != domain.ArrivalUnavailable {
		t.Fatalf("not available: %+v", a)
	}
}

func TestAfroTransactStockIsDatedFromAFulfilmentCentreAndPickupKeepsItsReadyLabel(t *testing.T) {
	p := &recordingPromise{answer: func(o port.PromiseOffer) port.PromiseQuote {
		if o.Method["type"] == "PICKUP" {
			return port.PromiseQuote{OfferID: o.OfferID, Available: true, ReadyAt: "2026-11-16T15:00:00Z", Label: "Ready for pickup Mon at 3:00 PM"}
		}
		return port.PromiseQuote{OfferID: o.OfferID, Available: true, EarliestDate: "2026-11-16", LatestDate: "2026-11-16"}
	}}
	fc := ArrivalRequest{Key: "d", Fulfilment: domain.FulfilledByAfroTransact, Option: domain.OptionDelivery}
	pk := ArrivalRequest{Key: "p", Fulfilment: domain.FulfilledByAfroTransact, Option: domain.OptionPickup}
	got := NewArrivalService(p, nil).Windows(context.Background(), "30301", []ArrivalRequest{fc, pk}, arrivalNow)
	if got["d"].Label != "Mon, Nov 16" || got["p"].Label != "Ready for pickup Mon at 3:00 PM" || !got["p"].Known() {
		t.Fatalf("%+v", got)
	}
	if p.asked[0].Origin["id"] != "fc_atlanta_ga" || p.asked[0].Method["type"] != "AFROTRANSACT_DELIVERY" || p.asked[1].Method["type"] != "PICKUP" {
		t.Fatalf("%+v", p.asked)
	}
}

func TestAPackageIsDatedOnlyWhenEveryLineIsAndCoversTheSlowestLine(t *testing.T) {
	slow := domain.ArrivalOf("2026-11-19", "2026-11-23", "", arrivalNow)
	fast := domain.ArrivalOf("2026-11-18", "2026-11-20", "Order within 3 hrs", arrivalNow)
	m := mergeArrivals([]domain.Arrival{fast, slow}, arrivalNow)
	if !m.Known() || m.EarliestDate != "2026-11-19" || m.LatestDate != "2026-11-23" || m.Label != "Nov 19 – 23" {
		t.Fatalf("%+v", m)
	}
	m = mergeArrivals([]domain.Arrival{fast, domain.NoArrival(domain.ArrivalNoOrigin)}, arrivalNow)
	if m.Known() || m.Reason != domain.ArrivalNoOrigin || m.Label != "" {
		t.Fatalf("one undated line leaves the package undated: %+v", m)
	}
	if m := mergeArrivals(nil, arrivalNow); m.Known() {
		t.Fatalf("%+v", m)
	}
}

// offersOrigin answers one seller offer's handling time and ship-from ZIP, as offers' public offer does.
type offersOrigin struct {
	port.StorefrontOffersClient
	byID map[string]port.PublicOffer
}

func (o offersOrigin) Offer(_ context.Context, id string) (port.OfferDetail, error) {
	if v, ok := o.byID[id]; ok {
		return port.OfferDetail{Offer: v, Buyable: true}, nil
	}
	return port.OfferDetail{}, port.ErrNotFound
}

func TestTheCartDatesASellerPackageFromTheOffersRealInputsAndLeavesOthersUndated(t *testing.T) {
	p := &recordingPromise{}
	offers := offersOrigin{byID: map[string]port.PublicOffer{
		"offer-ok":    {OfferID: "offer-ok", HandlingDays: 2, ShipFromZip: "30303"},
		"offer-nozip": {OfferID: "offer-nozip", HandlingDays: 2},
	}}
	mk := func(id, offer string) domain.CartPackage {
		return domain.CartPackage{PackageID: id, Fulfilment: domain.ShipsFromSeller, SellerOrgRaw: "org_laserfeet", Lines: []domain.CartLine{{LineID: "l-" + id, OfferID: offer}}}
	}
	pkgs := []domain.CartPackage{mk("ok", "offer-ok"), mk("nozip", "offer-nozip"), mk("gone", "offer-missing")}
	got := datePackages(context.Background(), NewArrivalService(p, nil), offers, "77042", pkgs,
		func(domain.CartPackage) []string { return []string{domain.OptionDelivery} }, arrivalNow, nil)
	if a := got["ok|"+domain.OptionDelivery]; !a.Known() || a.Label != "Nov 18 – 20" {
		t.Fatalf("%+v", a)
	}
	for _, k := range []string{"nozip", "gone"} {
		if a := got[k+"|"+domain.OptionDelivery]; a.Known() || a.Reason != domain.ArrivalNoOrigin {
			t.Fatalf("%s: %+v", k, a)
		}
	}
	if len(p.asked) != 1 || p.asked[0].Origin["handlingDays"] != 2 {
		t.Fatalf("only the package with a real origin is asked about: %+v", p.asked)
	}
	// without an offers service a seller package has no origin
	if a := datePackages(context.Background(), NewArrivalService(p, nil), nil, "77042", pkgs[:1],
		func(domain.CartPackage) []string { return []string{domain.OptionDelivery} }, arrivalNow, nil)["ok|"+domain.OptionDelivery]; a.Known() {
		t.Fatalf("%+v", a)
	}
}
