package app

import (
	"context"
	"log/slog"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ArrivalRequest asks for the delivery window of one package line or offer.
type ArrivalRequest struct {
	// Key identifies the request in the answer (a line id, an offer id).
	Key string
	// Fulfilment is the normalised fulfilment of the offer (domain.FulfilledByAfroTransact, ShipsFromSeller, AfroTransactDelivery).
	Fulfilment string
	// Option is the delivery option being dated: domain.OptionDelivery (also "" ), OptionPickup or OptionCarrier.
	Option      string
	SellerOrgID string
	// HandlingDays and ShipFromZip are the seller's real inputs from the offer: the handling time they configured (known
	// only when HandlingKnown) and the ZIP the offer ships from.
	HandlingDays  int
	HandlingKnown bool
	ShipFromZip   string
}

// ArrivalService is the one place a delivery window is worked out, for the cart, the checkout, the product page and the
// rails. A date comes from the promise service for real inputs (the seller's handling time, the offer's ship-from ZIP, the
// shopper's ZIP, the calendar with weekends and federal holidays) or not at all: there is no default, no "today plus N".
type ArrivalService interface {
	// Windows answers every request, by Key. A request with no real basis (no ZIP, a seller offer without a handling time or
	// ship-from ZIP, AfroTransact Delivery from a store, no promise service, a promise the service could not make) gets
	// domain.NoArrival with the reason; never a date.
	Windows(ctx context.Context, zip string, reqs []ArrivalRequest, now time.Time) map[string]domain.Arrival
}

type arrivalServiceImpl struct {
	promise port.PromiseClient
	log     *slog.Logger
}

var _ ArrivalService = (*arrivalServiceImpl)(nil)

// NewArrivalService returns the ArrivalService over the promise service (nil or disabled: every window is NO_SERVICE).
func NewArrivalService(promise port.PromiseClient, log *slog.Logger) ArrivalService {
	if log == nil {
		log = slog.Default()
	}
	return &arrivalServiceImpl{promise: promise, log: log}
}

var zip5Re = regexp.MustCompile(`^\d{5}$`)

// promiseBatch is the promise service's batch size (maxOffers).
const promiseBatch = 100

func (a *arrivalServiceImpl) Windows(ctx context.Context, zip string, reqs []ArrivalRequest, now time.Time) map[string]domain.Arrival {
	out := make(map[string]domain.Arrival, len(reqs))
	pickups := map[string]bool{}
	for _, r := range reqs {
		pickups[r.Key] = r.Option == domain.OptionPickup
	}
	zip = firstFive(zip)
	var asks []port.PromiseOffer
	for _, r := range reqs {
		switch {
		case !zip5Re.MatchString(zip):
			out[r.Key] = domain.NoArrival(domain.ArrivalNoPlace)
		case a.promise == nil:
			out[r.Key] = domain.NoArrival(domain.ArrivalNoService)
		default:
			if offer, ok := promiseOffer(r, zip); ok {
				asks = append(asks, offer)
			} else {
				out[r.Key] = domain.NoArrival(domain.ArrivalNoOrigin)
			}
		}
	}
	for start := 0; start < len(asks); start += promiseBatch {
		batch := asks[start:min(start+promiseBatch, len(asks))]
		quotes, err := a.promise.Promises(ctx, zip, batch)
		if err != nil {
			a.log.WarnContext(ctx, "promise service unavailable; no delivery dates shown", "error", err)
		}
		byID := make(map[string]port.PromiseQuote, len(quotes))
		for _, q := range quotes {
			byID[q.OfferID] = q
		}
		for _, o := range batch {
			q, ok := byID[o.OfferID]
			switch {
			case ok && q.Available && pickups[o.OfferID] && q.Label != "":
				// A pickup is "ready" at a time, not delivered in a window: the service's own label ("Ready for pickup today at 4:00 PM").
				day := firstNonEmpty(q.EarliestDate, strings.SplitN(q.ReadyAt, "T", 2)[0])
				out[o.OfferID] = domain.Arrival{Basis: domain.ArrivalPromise, EarliestDate: day, LatestDate: day, Label: q.Label, CutoffLabel: q.CutoffLabel}
			case !ok || !q.Available || q.EarliestDate == "":
				out[o.OfferID] = domain.NoArrival(domain.ArrivalUnavailable)
			default:
				out[o.OfferID] = domain.ArrivalOf(q.EarliestDate, q.LatestDate, q.CutoffLabel, now)
			}
		}
	}
	return out
}

// promiseOffer is the promise request for one arrival request, from its real origin; false when the origin is not known.
func promiseOffer(r ArrivalRequest, zip string) (port.PromiseOffer, bool) {
	switch r.Fulfilment {
	case domain.FulfilledByAfroTransact:
		method := map[string]any{"type": "AFROTRANSACT_DELIVERY"}
		switch r.Option {
		case domain.OptionPickup:
			method = map[string]any{"type": "PICKUP"}
		case domain.OptionCarrier:
			method = map[string]any{"type": "CARRIER", "serviceLevel": "GROUND"}
		}
		return port.PromiseOffer{OfferID: r.Key, Origin: map[string]any{"type": "FC", "id": fcFor(zip)}, Method: method}, true
	case domain.ShipsFromSeller:
		from := firstFive(r.ShipFromZip)
		if r.Option == domain.OptionPickup || !r.HandlingKnown || r.HandlingDays < 0 || !zip5Re.MatchString(from) || strings.TrimSpace(r.SellerOrgID) == "" {
			return port.PromiseOffer{}, false
		}
		return port.PromiseOffer{OfferID: r.Key,
			Origin: map[string]any{"type": "SELLER", "id": r.SellerOrgID, "shipFromZip": from, "handlingDays": r.HandlingDays},
			Method: map[string]any{"type": "CARRIER", "serviceLevel": "GROUND"}}, true
	}
	// AfroTransact Delivery from a seller's store: the promise service dates it from a fulfilment centre's driver zone, and a
	// store is not one, so there is no real basis here.
	return port.PromiseOffer{}, false
}

// mergeArrivals is the window of a package whose lines each have one: it is known only when every line's is, and it
// covers the slowest line (the latest earliest date and the latest latest date). With any line undated the package has
// no date and keeps that line's reason.
func mergeArrivals(list []domain.Arrival, now time.Time) domain.Arrival {
	if len(list) == 0 {
		return domain.NoArrival(domain.ArrivalNoOrigin)
	}
	earliest, latest, cutoff := "", "", ""
	for _, a := range list {
		if !a.Known() {
			return domain.NoArrival(firstNonEmpty(a.Reason, domain.ArrivalNoOrigin))
		}
		earliest, latest = max(earliest, a.EarliestDate), max(latest, a.LatestDate)
		if cutoff == "" {
			cutoff = a.CutoffLabel
		}
	}
	return domain.ArrivalOf(earliest, latest, cutoff, now)
}

// datePackages dates the delivery options of packages that ship now, in one batch to the promise service. For each
// package and option it asks per line (a seller's lines can leave from different places with different handling times) and
// merges them into the package's window (mergeArrivals). The answer is keyed "packageID|option"; a package or option with
// no real basis is there with its reason, never a date. offers (optional) supplies a seller offer's handling time and
// ship-from ZIP; without it seller packages have no origin.
func datePackages(ctx context.Context, arrival ArrivalService, offers port.StorefrontOffersClient, zip string, pkgs []domain.CartPackage,
	optionsOf func(domain.CartPackage) []string, now time.Time, log *slog.Logger) map[string]domain.Arrival {
	type origin struct {
		handling int
		known    bool
		zip      string
	}
	origins := map[string]origin{}
	var reqs []ArrivalRequest
	index := map[string][]string{} // "packageID|option" -> request keys
	for _, p := range pkgs {
		if len(p.Preorder) > 0 {
			continue
		}
		for _, opt := range optionsOf(p) {
			for _, l := range p.Lines {
				r := ArrivalRequest{Key: p.PackageID + "|" + opt + "|" + l.LineID, Fulfilment: p.Fulfilment, Option: opt, SellerOrgID: p.SellerOrgRaw}
				if p.Fulfilment == domain.ShipsFromSeller {
					o, ok := origins[l.OfferID]
					if !ok {
						o.handling, o.known, o.zip = offerOriginOf(ctx, offers, l.OfferID, log)
						origins[l.OfferID] = o
					}
					r.HandlingDays, r.HandlingKnown, r.ShipFromZip = o.handling, o.known, o.zip
				}
				reqs = append(reqs, r)
				index[p.PackageID+"|"+opt] = append(index[p.PackageID+"|"+opt], r.Key)
			}
		}
	}
	var windows map[string]domain.Arrival
	if arrival != nil {
		windows = arrival.Windows(ctx, zip, reqs, now)
	}
	out := make(map[string]domain.Arrival, len(index))
	for k, keys := range index {
		list := make([]domain.Arrival, 0, len(keys))
		for _, rk := range keys {
			a, ok := windows[rk]
			if !ok {
				a = domain.NoArrival(domain.ArrivalNoService)
			}
			list = append(list, a)
		}
		out[k] = mergeArrivals(list, now)
	}
	return out
}

// offerOriginOf reads what a seller-shipped offer says about where and when it leaves: the handling days the seller set and
// the ship-from ZIP. Unknown (offers unavailable, no ship-from ZIP) is reported as unknown, never defaulted.
func offerOriginOf(ctx context.Context, offers port.StorefrontOffersClient, offerID string, log *slog.Logger) (handling int, known bool, zip string) {
	if offers == nil || offerID == "" {
		return 0, false, ""
	}
	o, err := offers.Offer(ctx, offerID)
	if err != nil {
		if log != nil {
			log.InfoContext(ctx, "offer origin unavailable; the package is shown without a delivery date", "error", err)
		}
		return 0, false, ""
	}
	return o.Offer.HandlingDays, o.Offer.ShipFromZip != "", o.Offer.ShipFromZip
}
