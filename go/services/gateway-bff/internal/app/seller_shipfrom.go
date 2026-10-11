package app

import (
	"context"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerShipFromService manages the organisation's ship-from addresses in the seller service (the label form
// preselects the default). Reading needs Orders view; changing needs Orders edit.
type SellerShipFromService interface {
	List(ctx context.Context, c *domain.Caller) ([]domain.ShipFromAddress, error)
	Create(ctx context.Context, c *domain.Caller, in domain.ShipFromInput) (domain.ShipFromAddress, error)
	Update(ctx context.Context, c *domain.Caller, id string, in domain.ShipFromInput) (domain.ShipFromAddress, error)
	MakeDefault(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error)
	Delete(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error)
	// SetPickup turns "AfroTransact picks up from my store" on or off (S255).
	SetPickup(ctx context.Context, c *domain.Caller, id string, in domain.AfrotransactPickupRequest) (domain.ShipFromAddress, error)
	// SetCustomerPickup turns "customers can pick up their orders here" on or off: a carrier-gateway pickup location
	// (kind SELLER, the address's id) that checkout offers for the seller's packages.
	SetCustomerPickup(ctx context.Context, c *domain.Caller, id string, in domain.CustomerPickupRequest) (domain.ShipFromAddress, error)
}

type sellerShipFromImpl struct{ r *orgResolver }

var _ SellerShipFromService = (*sellerShipFromImpl)(nil)

// NewSellerShipFromService returns the SellerShipFromService.
func NewSellerShipFromService(d SellerDeps) SellerShipFromService {
	return &sellerShipFromImpl{r: newResolver(d)}
}

func shipFromOf(a port.ShipFromAddress) domain.ShipFromAddress {
	out := domain.ShipFromAddress{ID: a.ID, Label: a.Label, Name: a.Name, Company: a.Company, Phone: a.Phone, Line1: a.Line1,
		Line2: a.Line2, City: a.City, State: a.State, PostalCode: a.PostalCode, Country: firstNonEmpty(a.Country, "US"),
		IsDefault: a.IsDefault, Version: a.Version, AfrotransactPickup: domain.AfrotransactPickup{Hours: []domain.PickupWindow{}},
		CustomerPickup: domain.CustomerPickup{Hours: []domain.PickupWindow{}}}
	if p := a.Position; p != nil {
		out.Position = &domain.GeoPosition{Lat: p.Lat, Lng: p.Lng, Precision: strings.ToUpper(firstNonEmpty(p.Precision, "ADDRESS")), GeocodedAt: p.GeocodedAt}
	}
	if p := a.AfrotransactPickup; p != nil {
		out.AfrotransactPickup.Enabled, out.AfrotransactPickup.ZoneName, out.AfrotransactPickup.EnabledAt = p.Enabled, strPtr(p.ZoneName), p.EnabledAt
		for _, h := range p.Hours {
			out.AfrotransactPickup.Hours = append(out.AfrotransactPickup.Hours, domain.PickupWindow{Day: strings.ToUpper(h.Day), Opens: h.Opens, Closes: h.Closes})
		}
	}
	return out
}

func shipFromList(in []port.ShipFromAddress) []domain.ShipFromAddress {
	out := make([]domain.ShipFromAddress, 0, len(in))
	for _, a := range in {
		out = append(out, shipFromOf(a))
	}
	return out
}

func shipFromInput(in domain.ShipFromInput) port.ShipFromInput {
	t := strings.TrimSpace
	return port.ShipFromInput{Label: t(in.Label), Name: t(in.Name), Company: t(in.Company), Phone: t(in.Phone), Line1: t(in.Line1),
		Line2: t(in.Line2), City: t(in.City), State: strings.ToUpper(t(in.State)), PostalCode: t(in.PostalCode),
		Country: strings.ToUpper(firstNonEmpty(t(in.Country), "US")), MakeDefault: in.MakeDefault, Version: in.Version}
}

func (s *sellerShipFromImpl) List(ctx context.Context, c *domain.Caller) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	out := shipFromList(list)
	s.withCustomerPickup(ctx, c, sc.org.ID, out)
	return out, nil
}

// withCustomerPickup fills each address's customer pickup from carrier-gateway (best effort: off when unreadable).
func (s *sellerShipFromImpl) withCustomerPickup(ctx context.Context, c *domain.Caller, orgID string, out []domain.ShipFromAddress) {
	for i := range out {
		out[i].CustomerPickup = domain.CustomerPickup{Hours: []domain.PickupWindow{}}
	}
	if s.r.d.Carrier == nil || len(out) == 0 {
		return
	}
	locs, err := s.r.d.Carrier.ManagedPickupLocations(ctx, c.BearerToken, orgID)
	if err != nil {
		s.r.d.Log.WarnContext(ctx, "seller pickup locations unavailable", "error", err)
		return
	}
	byID := map[string]port.PickupLocation{}
	for _, l := range locs {
		byID[l.ID] = l
	}
	for i := range out {
		if l, ok := byID[out[i].ID]; ok {
			out[i].CustomerPickup = customerPickupOf(l)
		}
	}
}

// pickupWeekdays maps Seller Center days to carrier-gateway weekdays (0 = Sunday).
var pickupWeekdays = map[string]int{"SUNDAY": 0, "MONDAY": 1, "TUESDAY": 2, "WEDNESDAY": 3, "THURSDAY": 4, "FRIDAY": 5, "SATURDAY": 6}

func customerPickupOf(l port.PickupLocation) domain.CustomerPickup {
	out := domain.CustomerPickup{Enabled: l.Active, ReadyHours: l.ReadyHours, Hours: []domain.PickupWindow{}}
	for _, h := range l.Hours {
		for day, n := range pickupWeekdays {
			if n == h.Weekday {
				out.Hours = append(out.Hours, domain.PickupWindow{Day: day, Opens: h.Open, Closes: h.Close})
			}
		}
	}
	return out
}

// stateTimezones is the IANA zone of a US state's pickup hours (the state's main zone).
var stateTimezones = map[string]string{
	"CT": "America/New_York", "DE": "America/New_York", "DC": "America/New_York", "FL": "America/New_York", "GA": "America/New_York",
	"ME": "America/New_York", "MD": "America/New_York", "MA": "America/New_York", "MI": "America/Detroit", "NH": "America/New_York",
	"NJ": "America/New_York", "NY": "America/New_York", "NC": "America/New_York", "OH": "America/New_York", "PA": "America/New_York",
	"RI": "America/New_York", "SC": "America/New_York", "VT": "America/New_York", "VA": "America/New_York", "WV": "America/New_York",
	"IN": "America/Indiana/Indianapolis", "KY": "America/New_York",
	"AL": "America/Chicago", "AR": "America/Chicago", "IL": "America/Chicago", "IA": "America/Chicago", "KS": "America/Chicago",
	"LA": "America/Chicago", "MN": "America/Chicago", "MS": "America/Chicago", "MO": "America/Chicago", "NE": "America/Chicago",
	"ND": "America/Chicago", "OK": "America/Chicago", "SD": "America/Chicago", "TN": "America/Chicago", "TX": "America/Chicago", "WI": "America/Chicago",
	"CO": "America/Denver", "ID": "America/Boise", "MT": "America/Denver", "NM": "America/Denver", "UT": "America/Denver", "WY": "America/Denver",
	"AZ": "America/Phoenix", "CA": "America/Los_Angeles", "NV": "America/Los_Angeles", "OR": "America/Los_Angeles", "WA": "America/Los_Angeles",
	"AK": "America/Anchorage", "HI": "Pacific/Honolulu",
}

// customerPickupLocation is the carrier-gateway pickup location of an address.
func customerPickupLocation(a domain.ShipFromAddress, orgID string, in domain.CustomerPickupRequest) port.PickupLocation {
	l := port.PickupLocation{ID: a.ID, Kind: "SELLER", OwnerOrg: orgID, Name: firstNonEmpty(strings.TrimSpace(a.Company), strings.TrimSpace(a.Label), a.Name),
		Timezone: firstNonEmpty(stateTimezones[strings.ToUpper(a.State)], "America/Chicago"), ReadyHours: in.ReadyHours, Active: in.Enabled}
	if a.Company != "" && a.Label != "" && !strings.EqualFold(a.Company, a.Label) {
		l.Name = a.Company + " · " + a.Label
	}
	l.Address.Line1, l.Address.Line2, l.Address.City, l.Address.State, l.Address.PostalCode, l.Address.Country = a.Line1, a.Line2, a.City, a.State, a.PostalCode, firstNonEmpty(a.Country, "US")
	for _, h := range in.Hours {
		l.Hours = append(l.Hours, struct {
			Weekday int    `json:"weekday"`
			Open    string `json:"open"`
			Close   string `json:"close"`
		}{Weekday: pickupWeekdays[h.Day], Open: h.Opens, Close: h.Closes})
	}
	if a.Position != nil {
		l.Position = &port.GeoPoint{Lat: a.Position.Lat, Lng: a.Position.Lng}
	}
	return l
}

func (s *sellerShipFromImpl) SetCustomerPickup(ctx context.Context, c *domain.Caller, id string, in domain.CustomerPickupRequest) (domain.ShipFromAddress, error) {
	for i := range in.Hours {
		in.Hours[i].Day = strings.ToUpper(strings.TrimSpace(in.Hours[i].Day))
	}
	if in.Hours == nil {
		in.Hours = []domain.PickupWindow{}
	}
	if errs := in.Validate(); len(errs) > 0 {
		p := domain.ValidationProblem(errs)
		p.Code = domain.CodePickupInvalidHours
		return domain.ShipFromAddress{}, p
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	if s.r.d.Carrier == nil {
		return domain.ShipFromAddress{}, domain.UpstreamUnavailable("carrier-gateway")
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.ShipFromAddress{}, mapErr("seller", err)
	}
	for _, a := range shipFromList(list) {
		if a.ID != id {
			continue
		}
		if in.Enabled && len(in.Hours) == 0 {
			return domain.ShipFromAddress{}, domain.FieldProblem(domain.CodePickupInvalidHours, "Check the pickup hours", "hours", "Add the hours customers can pick up.")
		}
		saved, err := s.r.d.Carrier.SavePickupLocation(ctx, c.BearerToken, customerPickupLocation(a, sc.org.ID, in))
		if err != nil {
			return domain.ShipFromAddress{}, mapErr("carrier-gateway", err)
		}
		a.CustomerPickup = customerPickupOf(saved)
		return a, nil
	}
	return domain.ShipFromAddress{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Address not found", "This ship-from address doesn't exist.")
}

func (s *sellerShipFromImpl) Create(ctx context.Context, c *domain.Caller, in domain.ShipFromInput) (domain.ShipFromAddress, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ShipFromAddress{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	a, err := s.r.d.Sellers.CreateShipFrom(ctx, c.BearerToken, sc.org.ID, shipFromInput(in))
	if err != nil {
		return domain.ShipFromAddress{}, mapErr("seller", err)
	}
	return shipFromOf(a), nil
}

func (s *sellerShipFromImpl) Update(ctx context.Context, c *domain.Caller, id string, in domain.ShipFromInput) (domain.ShipFromAddress, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ShipFromAddress{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	a, err := s.r.d.Sellers.UpdateShipFrom(ctx, c.BearerToken, sc.org.ID, id, shipFromInput(in))
	if err != nil {
		return domain.ShipFromAddress{}, mapErr("seller", err)
	}
	return shipFromOf(a), nil
}

func (s *sellerShipFromImpl) MakeDefault(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.DefaultShipFrom(ctx, c.BearerToken, sc.org.ID, id)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return shipFromList(list), nil
}

func (s *sellerShipFromImpl) Delete(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.DeleteShipFrom(ctx, c.BearerToken, sc.org.ID, id)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return shipFromList(list), nil
}

func (s *sellerShipFromImpl) SetPickup(ctx context.Context, c *domain.Caller, id string, in domain.AfrotransactPickupRequest) (domain.ShipFromAddress, error) {
	for i := range in.Hours {
		in.Hours[i].Day = strings.ToUpper(strings.TrimSpace(in.Hours[i].Day))
	}
	if in.Hours == nil {
		in.Hours = []domain.PickupWindow{}
	}
	if errs := in.Validate(); len(errs) > 0 {
		p := domain.ValidationProblem(errs)
		p.Code = domain.CodePickupInvalidHours
		return domain.ShipFromAddress{}, p
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	a, err := s.r.d.Sellers.SetPickup(ctx, c.BearerToken, sc.org.ID, id, port.PickupRequest{Enabled: in.Enabled, Hours: in.Hours, Version: in.Version})
	if err != nil {
		return domain.ShipFromAddress{}, pickupErr(err)
	}
	return shipFromOf(a), nil
}

// pickupErr turns seller's pickup refusals into the problems the address screen explains.
func pickupErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("seller", err)
	}
	switch p.Code {
	case "seller.org-not-approved":
		return domain.NewProblem(http.StatusConflict, domain.CodePickupOrgNotApproved, "Not approved yet",
			"AfroTransact pickup opens once your seller account is approved.").WithCause(err)
	case "seller.invalid-pickup-hours":
		pr := domain.FieldProblem(domain.CodePickupInvalidHours, "Check the pickup hours", "hours",
			firstNonEmpty(p.Detail, "Add 1 to 14 pickup windows that don't overlap, each opening before it closes."))
		for k, v := range p.Fields {
			pr.Errors[k] = v
		}
		return pr.WithCause(err)
	case "seller.ship-from-not-geocoded":
		return domain.FieldProblem(domain.CodePickupNotGeocoded, "We couldn't locate this address", "enabled",
			"Drivers need a street-level location, and we could only place this address by its ZIP code. Check the street address and save it again.").WithCause(err)
	case "seller.pickup-outside-delivery-area":
		return domain.FieldProblem(domain.CodePickupOutsideDeliveryArea, "Outside the delivery area", "enabled",
			firstNonEmpty(p.Detail, "This address is too far from an AfroTransact delivery zone for driver pickups.")).WithCause(err)
	case "seller.geo-unavailable":
		return domain.UpstreamUnavailable("geo").WithCause(err)
	}
	return mapErr("seller", err)
}
