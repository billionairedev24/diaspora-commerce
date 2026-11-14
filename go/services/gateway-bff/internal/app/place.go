package app

import (
	"context"
	"log/slog"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Destination is where the shopper is, from the place the site already has, for everything that depends on it (the tax
// estimate, the delivery window). Source is SAVED_ADDRESS, CHOSEN_PLACE, DETECTED_PLACE or DEFAULT_PLACE; Precision is ZIP or
// CITY. ZIP is empty when no place is known: nothing may then be guessed.
type Destination struct {
	ZIP, City, State, Source, Precision string
}

// PlaceResolver works the shopper's place out in one priority order for every surface: the saved default (else first
// usable) address, then the delivery place chosen under "Deliver to", then the place detected from the edge's headers,
// then the market's default (the place the header shows).
type PlaceResolver interface {
	// SavedAddress is the signed-in shopper's saved address to use ("", nil for a guest or no address book). When it
	// cannot be used it says why: ADDRESS_INCOMPLETE (no state or ZIP) or SERVICE_UNAVAILABLE (the address book could not be read).
	SavedAddress(ctx context.Context, c *domain.Caller) (*port.IdentityAddress, string)
	// KnownPlace is the chosen, detected or default place with a real ZIP and state (nil when there is none), its source
	// (CHOSEN_PLACE, DETECTED_PLACE, DEFAULT_PLACE) and its precision ("" = ZIP, CITY).
	KnownPlace(ctx context.Context, c *domain.Caller) (*port.IdentityAddress, string, string)
	// Destination is the first of SavedAddress and KnownPlace that gives a ZIP (empty Destination when none does).
	Destination(ctx context.Context, c *domain.Caller) Destination
}

// PlaceDeps are the PlaceResolver's collaborators; each is optional (a missing one just contributes no place).
type PlaceDeps struct {
	Identity port.IdentityClient
	Location LocationService
	// ZipLookup gives the city and state of a ZIP (geo's ZIP table), for a stored place that lost its state.
	ZipLookup func(ctx context.Context, zip string) (city, state string, err error)
	Log       *slog.Logger
}

func (d PlaceDeps) log() *slog.Logger {
	if d.Log == nil {
		return slog.Default()
	}
	return d.Log
}

type placeResolverImpl struct{ d PlaceDeps }

var _ PlaceResolver = (*placeResolverImpl)(nil)

// NewPlaceResolver returns the PlaceResolver.
func NewPlaceResolver(d PlaceDeps) PlaceResolver { return &placeResolverImpl{d: d} }

func (r *placeResolverImpl) Destination(ctx context.Context, c *domain.Caller) Destination {
	if a, _ := r.SavedAddress(ctx, c); a != nil {
		return Destination{ZIP: firstFive(a.PostalCode), City: a.City, State: strings.ToUpper(a.State), Source: domain.TaxPlaceSavedAddress, Precision: domain.PrecisionZip}
	}
	if a, source, precision := r.KnownPlace(ctx, c); a != nil {
		return Destination{ZIP: a.PostalCode, City: a.City, State: a.State, Source: source, Precision: firstNonEmpty(precision, domain.PrecisionZip)}
	}
	return Destination{}
}

// SavedAddress is the signed-in shopper's saved address the cart estimate is worked out for: the default
// address, or, when none is marked default, the first saved one that can be taxed. It says why there is none, so the cart
// never falls back to asking for a ZIP code it does not need: a saved address that lacks a state or ZIP code is
// ADDRESS_INCOMPLETE (the shopper completes it), an address book that could not be read is SERVICE_UNAVAILABLE (Retry).
// No address at all, or a guest, gives ("", nil): the client asks for a ZIP code.
func (r *placeResolverImpl) SavedAddress(ctx context.Context, c *domain.Caller) (*port.IdentityAddress, string) {
	if c == nil || c.BearerToken == "" || r.d.Identity == nil {
		return nil, ""
	}
	addrs, err := r.d.Identity.Addresses(ctx, c.BearerToken)
	if err != nil {
		r.d.log().WarnContext(ctx, "address book unavailable for the cart tax estimate", "error", err)
		return nil, domain.TaxReasonServiceUnavailable
	}
	taxable := func(a *port.IdentityAddress) bool {
		return strings.TrimSpace(a.State) != "" && strings.TrimSpace(a.PostalCode) != ""
	}
	for i := range addrs {
		if a := &addrs[i]; a.IsDefault {
			if taxable(a) {
				return a, ""
			}
			return nil, domain.TaxReasonAddressIncomplete
		}
	}
	for i := range addrs {
		if a := &addrs[i]; taxable(a) {
			return a, ""
		}
	}
	if len(addrs) > 0 {
		return nil, domain.TaxReasonAddressIncomplete
	}
	return nil, ""
}

// KnownPlace is the place the storefront already has for this visitor when there is no usable saved address: the delivery
// place the shopper chose, else the one detected from the edge's visitor-location headers, else the market's default (the
// same place the header shows as "Deliver to"). It returns a destination with a real five-digit ZIP and a state, the source
// and the precision (CITY when the ZIP was resolved from a detected city), or nil when there is none: the cart then asks
// for a ZIP code. It never invents a ZIP: every one comes from the session, geo's ZIP table or the market configuration.
func (r *placeResolverImpl) KnownPlace(ctx context.Context, c *domain.Caller) (*port.IdentityAddress, string, string) {
	if r.d.Location == nil || c == nil {
		return nil, "", ""
	}
	loc := r.d.Location.Current(ctx, c)
	zip := firstFive(loc.ZIP)
	if !zipOnly.MatchString(zip) {
		return nil, "", ""
	}
	city, state := strings.TrimSpace(loc.City), strings.ToUpper(strings.TrimSpace(loc.State))
	if state == "" && r.d.ZipLookup != nil {
		// A stored place that lost its state (geo was down when it was saved): the ZIP table gives it back.
		if lc, ls, err := r.d.ZipLookup(ctx, zip); err == nil && strings.TrimSpace(ls) != "" {
			city, state = firstNonEmpty(city, lc), strings.ToUpper(strings.TrimSpace(ls))
		}
	}
	if state == "" {
		return nil, "", ""
	}
	source := domain.TaxPlaceDefault
	switch loc.Source {
	case domain.LocationChosen:
		source = domain.TaxPlaceChosen
	case domain.LocationDetected:
		source = domain.TaxPlaceDetected
	}
	precision := ""
	if loc.Precision == domain.PrecisionCity {
		precision = domain.PrecisionCity
	}
	return &port.IdentityAddress{Country: "US", State: state, PostalCode: zip, City: city}, source, precision
}
