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
	// PickupPrompt says whether to show the one-time "let customers collect orders here" card (and the small nudge on
	// checkout settings) and the address it is pre-filled from. Pickup is never switched on by it.
	PickupPrompt(ctx context.Context, c *domain.Caller) (domain.PickupPrompt, error)
	// AnswerPickupPrompt records "Not now" (asks again after the staff-set number of days) or "Don't ask again", per
	// organisation, in carrier-gateway.
	AnswerPickupPrompt(ctx context.Context, c *domain.Caller, answer string) (domain.PickupPrompt, error)
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
		CustomerPickup: domain.CustomerPickup{Hours: []domain.PickupWindow{}, Issues: []domain.PickupIssue{}}}
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
	defer func() {
		for i := range out {
			out[i].CustomerPickup = withPickupIssues(out[i], out[i].CustomerPickup)
		}
	}()
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

// withPickupIssues says why an address is not (fully) offered to customers at checkout: pickup off, no opening hours, or an
// address geo-service could not place at street level (customers farther than the same ZIP area then do not see it, or see
// an approximate distance). Checkout reads exactly these facts (carrier-gateway location active + hours + position).
func withPickupIssues(a domain.ShipFromAddress, cp domain.CustomerPickup) domain.CustomerPickup {
	cp.Issues = []domain.PickupIssue{}
	switch {
	case !cp.Enabled:
		cp.Issues = append(cp.Issues, domain.PickupIssue{Code: domain.PickupIssueNotEnabled, Blocking: true,
			Message: "Customers can't pick up here yet. Turn on pickup and add the hours you're open."})
	case len(cp.Hours) == 0:
		cp.Issues = append(cp.Issues, domain.PickupIssue{Code: domain.PickupIssueNoHours, Blocking: true,
			Message: "Add the days and hours customers can collect their orders."})
	}
	switch {
	case a.Position == nil:
		cp.Issues = append(cp.Issues, domain.PickupIssue{Code: domain.PickupIssueNotLocated,
			Message: "We couldn't find this address on the map. Only customers in the same ZIP area will see it as a pickup point. Check the address."})
	case !strings.EqualFold(a.Position.Precision, "ADDRESS") && !strings.EqualFold(a.Position.Precision, "POINT"):
		cp.Issues = append(cp.Issues, domain.PickupIssue{Code: domain.PickupIssueApprox,
			Message: "We could only place this address by its ZIP area, so distances to customers are approximate."})
	}
	cp.Ready = cp.Enabled && len(cp.Hours) > 0
	return cp
}

func customerPickupOf(l port.PickupLocation) domain.CustomerPickup {
	out := domain.CustomerPickup{Enabled: l.Active, ReadyHours: l.ReadyHours, Hours: []domain.PickupWindow{}, EnabledAt: l.EnabledAt}
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
		Timezone: firstNonEmpty(stateTimezones[strings.ToUpper(a.State)], "America/Chicago"), ReadyHours: in.ReadyHours, Active: in.Enabled, HoursConfirmed: in.HoursConfirmed}
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
		if in.Enabled && !in.HoursConfirmed {
			// Keeping pickup on while editing needs no new confirmation; switching it on does.
			if on, err := s.pickupIsOn(ctx, c, sc.org.ID, id); err != nil {
				return domain.ShipFromAddress{}, err
			} else if !on {
				return domain.ShipFromAddress{}, domain.FieldProblem(domain.CodePickupHoursNotConfirmed, "Confirm the opening hours", "hoursConfirmed",
					"Confirm that someone will be there during these hours before pickup is switched on.")
			}
		}
		saved, err := s.r.d.Carrier.SavePickupLocation(ctx, c.BearerToken, customerPickupLocation(a, sc.org.ID, in))
		if err != nil {
			return domain.ShipFromAddress{}, mapErr("carrier-gateway", err)
		}
		s.r.d.Log.InfoContext(ctx, "store pickup saved", "org", sc.org.ID, "address", a.ID, "enabled", saved.Active, "by", c.Subject)
		a.CustomerPickup = withPickupIssues(a, customerPickupOf(saved))
		return a, nil
	}
	return domain.ShipFromAddress{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Address not found", "This ship-from address doesn't exist.")
}

// pickupIsOn reads carrier-gateway: is the address's pickup place active now (an unreadable answer is an error, so
// pickup is never switched on on a guess).
func (s *sellerShipFromImpl) pickupIsOn(ctx context.Context, c *domain.Caller, orgID, id string) (bool, error) {
	locs, err := s.r.d.Carrier.ManagedPickupLocations(ctx, c.BearerToken, orgID)
	if err != nil {
		return false, mapErr("carrier-gateway", err)
	}
	for _, l := range locs {
		if l.ID == id {
			return l.Active, nil
		}
	}
	return false, nil
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
	out := shipFromOf(a)
	s.resyncCustomerPickup(ctx, c, sc.org.ID, &out)
	return out, nil
}

// resyncCustomerPickup keeps the pickup place customers see in step with the address after an edit: checkout reads the
// carrier-gateway copy (address, ZIP, position), which would otherwise keep the old street and ZIP, so shoppers near the
// new address would not see it. Hours, ready time and on/off are kept. Best effort: a failure is logged and the seller can
// save the pickup settings again.
func (s *sellerShipFromImpl) resyncCustomerPickup(ctx context.Context, c *domain.Caller, orgID string, a *domain.ShipFromAddress) {
	if s.r.d.Carrier == nil {
		return
	}
	locs, err := s.r.d.Carrier.ManagedPickupLocations(ctx, c.BearerToken, orgID)
	if err != nil {
		s.r.d.Log.WarnContext(ctx, "seller pickup locations unavailable; pickup place not refreshed", "error", err)
		return
	}
	for _, l := range locs {
		if l.ID != a.ID {
			continue
		}
		cp := customerPickupOf(l)
		saved, err := s.r.d.Carrier.SavePickupLocation(ctx, c.BearerToken, customerPickupLocation(*a, orgID,
			domain.CustomerPickupRequest{Enabled: cp.Enabled, Hours: cp.Hours, ReadyHours: cp.ReadyHours}))
		if err != nil {
			s.r.d.Log.WarnContext(ctx, "pickup place not refreshed after an address edit", "error", err)
			cp = customerPickupOf(l)
		} else {
			cp = customerPickupOf(saved)
		}
		a.CustomerPickup = withPickupIssues(*a, cp)
		return
	}
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

// PickupPrompt builds the card's state. Anything unknown (carrier-gateway unreachable) hides the card and the nudge: a seller
// is never nagged to enable pickup on a guess, and never has it enabled by this call.
func (s *sellerShipFromImpl) PickupPrompt(ctx context.Context, c *domain.Caller) (domain.PickupPrompt, error) {
	out := domain.PickupPrompt{State: domain.PickupPromptStateNone}
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return out, err
	}
	if !sc.approved || sc.kind() != domain.OrgKindSeller {
		return out, nil
	}
	if sc, err = s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView); err != nil {
		return out, err
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return out, mapErr("seller", err)
	}
	addrs := shipFromList(list)
	out.Eligible = len(addrs) > 0
	if !out.Eligible || s.r.d.Carrier == nil {
		return out, nil
	}
	locs, err := s.r.d.Carrier.ManagedPickupLocations(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		s.r.d.Log.WarnContext(ctx, "pickup card hidden: pickup places unreadable", "error", err)
		return out, nil
	}
	for _, l := range locs {
		out.HasPickupPlace = out.HasPickupPlace || l.Active
	}
	if out.HasPickupPlace {
		return out, nil
	}
	pre := addrs[0]
	for _, a := range addrs {
		if a.IsDefault {
			pre = a
			break
		}
	}
	out.Address = &domain.PickupPromptAddress{AddressID: pre.ID, Name: firstNonEmpty(pre.Company, pre.Label, pre.Name), Line1: pre.Line1, Line2: pre.Line2,
		City: pre.City, State: pre.State, PostalCode: pre.PostalCode, Phone: pre.Phone}
	st, err := s.r.d.Carrier.PickupPrompt(ctx, c.BearerToken)
	if err != nil {
		s.r.d.Log.WarnContext(ctx, "pickup card hidden: answer unreadable", "error", err)
		out.Address = nil
		return out, nil
	}
	out.Nudge = true
	out.State, out.SnoozedUntil = firstNonEmpty(st.State, domain.PickupPromptStateNone), st.SnoozedUntil
	out.Show = st.Due
	return out, nil
}

func (s *sellerShipFromImpl) AnswerPickupPrompt(ctx context.Context, c *domain.Caller, answer string) (domain.PickupPrompt, error) {
	action := ""
	switch strings.ToUpper(strings.TrimSpace(answer)) {
	case domain.PickupPromptNotNow:
		action = "SNOOZE"
	case domain.PickupPromptDontAsk:
		action = "NEVER"
	default:
		return domain.PickupPrompt{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the answer", "answer", "Answer NOT_NOW or DONT_ASK_AGAIN.")
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit); err != nil {
		return domain.PickupPrompt{}, err
	}
	if s.r.d.Carrier == nil {
		return domain.PickupPrompt{}, domain.UpstreamUnavailable("carrier-gateway")
	}
	if _, err := s.r.d.Carrier.AnswerPickupPrompt(ctx, c.BearerToken, action); err != nil {
		return domain.PickupPrompt{}, mapErr("carrier-gateway", err)
	}
	return s.PickupPrompt(ctx, c)
}
