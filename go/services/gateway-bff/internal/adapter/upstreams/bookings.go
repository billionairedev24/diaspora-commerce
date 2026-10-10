package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- bookings (contracts/openapi/bookings.yaml)

type bookingsImpl struct {
	c   httpclient.Client
	now func() time.Time
}

var _ port.BookingsClient = (*bookingsImpl)(nil)

// NewBookings returns the bookings client. The Seller Center's "me" routes map onto bookings'
// /v1/providers/{orgId}/… with the active organisation of the member's token; booking actions use
// /v1/bookings/{id}/{confirm|decline|propose-time|complete|no-show|cancel} with an Idempotency-Key.
func NewBookings(c httpclient.Client, now func() time.Time) port.BookingsClient {
	if now == nil {
		now = time.Now
	}
	return &bookingsImpl{c: c, now: now}
}

func providerPath(p port.ProviderRef, rest string) string {
	return "/v1/providers/" + url.PathEscape(p.OrgID) + rest
}

// ---- wire shapes (bookings components)

type bkTimeRange struct {
	Start string `json:"start"`
	End   string `json:"end"`
}

type bkBooking struct {
	ID        string `json:"id"`
	Reference string `json:"reference"`
	Customer  struct {
		Name string `json:"name"`
	} `json:"customer"`
	Service struct {
		Name            string `json:"name"`
		DurationMinutes int    `json:"durationMinutes"`
		PriceMinor      int64  `json:"priceMinor"`
		DepositMinor    int64  `json:"depositMinor"`
		Currency        string `json:"currency"`
		MemberName      string `json:"memberName"`
	} `json:"service"`
	Status           string     `json:"status"`
	StartAt          time.Time  `json:"startAt"`
	EndAt            time.Time  `json:"endAt"`
	ProposedStartAt  *time.Time `json:"proposedStartAt"`
	Notes            string     `json:"notes"`
	RequestExpiresAt *time.Time `json:"requestExpiresAt"`
	Payment          *struct {
		State         string `json:"state"`
		RefundedMinor int64  `json:"refundedMinor"`
	} `json:"payment"`
}

func depositState(b bkBooking) string {
	if b.Payment == nil {
		return "none"
	}
	if b.Payment.RefundedMinor > 0 {
		return "refunded"
	}
	switch b.Payment.State {
	case "AUTHORIZED":
		return "held"
	case "DEPOSIT_CAPTURED", "CAPTURED":
		return "captured"
	case "RELEASED":
		return "released"
	}
	return "none"
}

func (b bkBooking) card() domain.BookingCard {
	var note, practitioner *string
	if strings.TrimSpace(b.Notes) != "" {
		n := b.Notes
		note = &n
	}
	if b.Service.MemberName != "" {
		m := b.Service.MemberName
		practitioner = &m
	}
	return domain.BookingCard{ID: b.ID, Reference: b.Reference, CustomerName: b.Customer.Name, ServiceName: b.Service.Name,
		StartAt: b.StartAt, EndAt: b.EndAt, DurationMinutes: b.Service.DurationMinutes, Status: b.Status,
		PriceMinor: b.Service.PriceMinor, DepositMinor: b.Service.DepositMinor, Currency: strings.ToUpper(b.Service.Currency),
		DepositState: depositState(b), Note: note, Practitioner: practitioner, ExpiresAt: b.RequestExpiresAt,
		ProposedStartAt: b.ProposedStartAt}
}

func cards(list []bkBooking) []domain.BookingCard {
	out := make([]domain.BookingCard, 0, len(list))
	for _, b := range list {
		out = append(out, b.card())
	}
	return out
}

// Weekday keys: bookings uses MONDAY…SUNDAY, the Seller Center MON…SUN.
var longDays = map[string]string{"MON": "MONDAY", "TUE": "TUESDAY", "WED": "WEDNESDAY", "THU": "THURSDAY", "FRI": "FRIDAY",
	"SAT": "SATURDAY", "SUN": "SUNDAY"}

func ranges(in []bkTimeRange) []domain.TimeRange {
	out := make([]domain.TimeRange, 0, len(in))
	for _, r := range in {
		out = append(out, domain.TimeRange{Start: r.Start, End: r.End})
	}
	return out
}

// ---- calendar and requests

func (b *bookingsImpl) Calendar(ctx context.Context, bearer string, p port.ProviderRef, from, to time.Time) (port.BookingsCalendar, error) {
	var cal struct {
		TimeZone string `json:"timeZone"`
		Days     []struct {
			Date string        `json:"date"`
			Open []bkTimeRange `json:"open"`
		} `json:"days"`
		Bookings []bkBooking `json:"bookings"`
	}
	q := url.Values{"from": {from.Format(time.DateOnly)}, "to": {to.Format(time.DateOnly)}}
	if _, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/calendar"), Query: q, Bearer: bearer}, &cal); err != nil {
		return port.BookingsCalendar{}, err
	}
	// Open hours per weekday: the first day of the range that falls on it (members' hours are merged by bookings).
	week := map[string][]domain.TimeRange{}
	for _, d := range cal.Days {
		t, err := time.Parse(time.DateOnly, d.Date)
		if err != nil {
			continue
		}
		key := strings.ToUpper(t.Weekday().String()[:3])
		if _, seen := week[key]; !seen || len(week[key]) == 0 {
			week[key] = ranges(d.Open)
		}
	}
	open := make([]domain.DayHours, 0, len(week))
	for _, d := range domain.Weekdays {
		if r, ok := week[d]; ok {
			open = append(open, domain.DayHours{Day: d, Ranges: r})
		}
	}
	return port.BookingsCalendar{Timezone: cal.TimeZone, OpenHours: open, Bookings: cards(cal.Bookings)}, nil
}

func (b *bookingsImpl) bookings(ctx context.Context, bearer string, p port.ProviderRef, from, to time.Time, status string) ([]bkBooking, error) {
	q := url.Values{"from": {from.UTC().Format(time.RFC3339)}, "to": {to.UTC().Format(time.RFC3339)}}
	if status != "" {
		q.Set("status", status)
	}
	var list []bkBooking
	if _, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/bookings"), Query: q, Bearer: bearer}, &list); err != nil {
		return nil, err
	}
	return list, nil
}

// Requests are bookings waiting for the provider (new requests and customer reschedule requests) in the booking
// horizon.
func (b *bookingsImpl) Requests(ctx context.Context, bearer string, p port.ProviderRef) ([]domain.BookingCard, error) {
	now := b.now()
	var out []domain.BookingCard
	for _, st := range []string{"REQUESTED", "RESCHEDULE_REQUESTED"} {
		list, err := b.bookings(ctx, bearer, p, now.AddDate(0, 0, -1), now.AddDate(1, 0, 0), st)
		if err != nil {
			return nil, err
		}
		out = append(out, cards(list)...)
	}
	return out, nil
}

var bookingActionPaths = map[string]string{"CONFIRM": "confirm", "DECLINE": "decline", "PROPOSE": "propose-time", "COMPLETE": "complete",
	"NO_SHOW": "no-show", "CANCEL": "cancel"}

func (b *bookingsImpl) Act(ctx context.Context, bearer, key, id, action, reason string, proposed *time.Time) (domain.BookingCard, error) {
	seg, ok := bookingActionPaths[action]
	if !ok {
		return domain.BookingCard{}, fmt.Errorf("bookings: unknown action %q", action)
	}
	var body any
	switch action {
	case "DECLINE", "CANCEL":
		body = map[string]string{"reason": reason}
	case "PROPOSE":
		if proposed != nil {
			body = map[string]string{"startAt": proposed.UTC().Format(time.RFC3339)}
		}
	}
	if key == "" {
		// Bookings requires an Idempotency-Key on every write; one action per booking is the natural unit.
		key = "seller-center:" + id + ":" + seg
	}
	path := "/v1/bookings/" + url.PathEscape(id) + "/" + seg
	if action == "CANCEL" {
		// cancel answers a CancelResponse {booking, …}.
		var out struct {
			Booking bkBooking `json:"booking"`
		}
		_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: path, Bearer: bearer, Header: idem(key),
			RetryUnsafe: true, JSON: body}, &out)
		return out.Booking.card(), err
	}
	var out bkBooking
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: path, Bearer: bearer, Header: idem(key),
		RetryUnsafe: true, JSON: body}, &out)
	return out.card(), err
}

// ---- working hours: provider settings (time zone, rules) + the member's weekly hours + time off

type bkRules struct {
	LeadMinutes          int  `json:"leadMinutes"`
	HorizonDays          int  `json:"horizonDays"`
	BufferMinutes        int  `json:"bufferMinutes"`
	StepMinutes          int  `json:"stepMinutes"`
	MaxPerDay            int  `json:"maxPerDay"`
	InstantBooking       bool `json:"instantBooking"`
	AutoAcceptReschedule bool `json:"autoAcceptReschedule"`
}

type bkProvider struct {
	DisplayName string  `json:"displayName"`
	TimeZone    string  `json:"timeZone"`
	Currency    string  `json:"currency"`
	Location    string  `json:"location"`
	Rules       bkRules `json:"rules"`
	Policy      *struct {
		Kind      string `json:"kind"`
		FreeHours int    `json:"freeHours"`
		Tiers     []struct {
			HoursBefore int `json:"hoursBefore"`
			ShareBps    int `json:"shareBps"`
		} `json:"tiers"`
		NoShow          string `json:"noShow"`
		RescheduleHours int    `json:"rescheduleHours"`
		MaxReschedules  int    `json:"maxReschedules"`
	} `json:"policy"`
}

type bkMember struct {
	ID       string `json:"id"`
	UserID   string `json:"userId"`
	Role     string `json:"role"`
	Bookable bool   `json:"bookable"`
	Active   bool   `json:"active"`
	Hours    struct {
		Days map[string][]bkTimeRange `json:"days"`
	} `json:"hours"`
}

type bkOverride struct {
	Date   string        `json:"date"`
	Closed bool          `json:"closed"`
	Ranges []bkTimeRange `json:"ranges"`
	Note   string        `json:"note"`
}

func (b *bookingsImpl) settings(ctx context.Context, bearer string, p port.ProviderRef) (bkProvider, error) {
	var out bkProvider
	_, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/settings"), Bearer: bearer}, &out)
	return out, err
}

// member is the signed-in member's team row, else the owner's, else the first active one.
func (b *bookingsImpl) member(ctx context.Context, bearer string, p port.ProviderRef) (bkMember, error) {
	var team []bkMember
	if _, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/team"), Bearer: bearer}, &team); err != nil {
		return bkMember{}, err
	}
	pick := func(ok func(bkMember) bool) (bkMember, bool) {
		i := slices.IndexFunc(team, func(m bkMember) bool { return m.Active && ok(m) })
		if i < 0 {
			return bkMember{}, false
		}
		return team[i], true
	}
	if m, ok := pick(func(m bkMember) bool { return p.UserID != "" && m.UserID == p.UserID }); ok {
		return m, nil
	}
	if m, ok := pick(func(m bkMember) bool { return m.Role == "OWNER" }); ok {
		return m, nil
	}
	if m, ok := pick(func(bkMember) bool { return true }); ok {
		return m, nil
	}
	return bkMember{}, &port.UpstreamProblem{Upstream: "bookings", Status: http.StatusNotFound, Code: "bookings.member-not-found",
		Detail: "Add yourself to the team in Provider settings first"}
}

func (b *bookingsImpl) Availability(ctx context.Context, bearer string, p port.ProviderRef) (domain.Availability, error) {
	set, err := b.settings(ctx, bearer, p)
	if err != nil {
		return domain.Availability{}, err
	}
	m, err := b.member(ctx, bearer, p)
	if err != nil {
		return domain.Availability{}, err
	}
	a := domain.Availability{Timezone: set.TimeZone, Rules: domain.BookingRules(set.Rules), BlockedDates: []domain.BlockedDate{}}
	for _, d := range domain.Weekdays {
		a.Weekly = append(a.Weekly, domain.DayHours{Day: d, Ranges: ranges(m.Hours.Days[longDays[d]])})
	}
	loc, lerr := time.LoadLocation(set.TimeZone)
	if lerr != nil {
		loc = time.UTC
	}
	today := b.now().In(loc)
	horizon := max(set.Rules.HorizonDays, 30)
	var overrides []bkOverride
	q := url.Values{"from": {today.Format(time.DateOnly)}, "to": {today.AddDate(0, 0, horizon).Format(time.DateOnly)}}
	if _, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/team/"+url.PathEscape(m.ID)+"/overrides"), Query: q,
		Bearer: bearer}, &overrides); err != nil {
		return domain.Availability{}, err
	}
	for _, o := range overrides {
		if o.Closed {
			a.BlockedDates = append(a.BlockedDates, domain.BlockedDate{Date: o.Date, Note: o.Note})
		}
	}
	return a, nil
}

func (b *bookingsImpl) SaveAvailability(ctx context.Context, bearer string, p port.ProviderRef, a domain.Availability) (domain.Availability, error) {
	set, err := b.settings(ctx, bearer, p)
	if err != nil {
		return domain.Availability{}, err
	}
	m, err := b.member(ctx, bearer, p)
	if err != nil {
		return domain.Availability{}, err
	}
	settings := map[string]any{"displayName": set.DisplayName, "timeZone": firstNonEmptyStr(a.Timezone, set.TimeZone),
		"rules": bkRules(a.Rules)}
	if set.Currency != "" {
		settings["currency"] = set.Currency
	}
	if set.Location != "" {
		settings["location"] = set.Location
	}
	if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: providerPath(p, "/settings"), Bearer: bearer,
		JSON: settings}, nil); err != nil {
		return domain.Availability{}, err
	}
	days := map[string][]bkTimeRange{}
	for _, d := range a.Weekly {
		if len(d.Ranges) == 0 {
			continue
		}
		rs := make([]bkTimeRange, 0, len(d.Ranges))
		for _, r := range d.Ranges {
			rs = append(rs, bkTimeRange(r))
		}
		days[longDays[strings.ToUpper(d.Day)]] = rs
	}
	memberPath := providerPath(p, "/team/"+url.PathEscape(m.ID))
	if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: memberPath + "/hours", Bearer: bearer,
		JSON: map[string]any{"days": days}}, nil); err != nil {
		return domain.Availability{}, err
	}
	// Time off: add the new blocked dates, remove the ones the provider dropped.
	current, err := b.Availability(ctx, bearer, p)
	if err != nil {
		return domain.Availability{}, err
	}
	want := map[string]domain.BlockedDate{}
	for _, d := range a.BlockedDates {
		want[d.Date] = d
	}
	for _, d := range current.BlockedDates {
		if _, keep := want[d.Date]; !keep {
			if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodDelete, Path: memberPath + "/overrides/" + url.PathEscape(d.Date),
				Bearer: bearer}, nil); err != nil {
				return domain.Availability{}, err
			}
		}
	}
	have := map[string]bool{}
	for _, d := range current.BlockedDates {
		have[d.Date] = true
	}
	for _, d := range a.BlockedDates {
		if !have[d.Date] {
			if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: memberPath + "/overrides", Bearer: bearer,
				JSON: bkOverride{Date: d.Date, Closed: true, Ranges: []bkTimeRange{}, Note: d.Note}}, nil); err != nil {
				return domain.Availability{}, err
			}
		}
	}
	return b.Availability(ctx, bearer, p)
}

func firstNonEmptyStr(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}

// ---- services & prices

type bkService struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Description     string   `json:"description"`
	Category        string   `json:"category"`
	DurationMinutes int      `json:"durationMinutes"`
	PriceMinor      int64    `json:"priceMinor"`
	DepositMinor    int64    `json:"depositMinor"`
	Currency        string   `json:"currency"`
	Modes           []string `json:"modes"`
	AddOns          []any    `json:"addOns"`
	Active          bool     `json:"active"`
	TakeHome        *struct {
		CommissionBps   int   `json:"commissionBps"`
		CommissionMinor int64 `json:"commissionMinor"`
		CardFeesMinor   int64 `json:"cardFeesMinor"`
		TakeHomeMinor   int64 `json:"takeHomeMinor"`
	} `json:"takeHome"`
}

func (s bkService) offer() domain.ServiceOffer {
	o := domain.ServiceOffer{ID: s.ID, Name: s.Name, Description: s.Description, DurationMinutes: s.DurationMinutes,
		PriceMinor: s.PriceMinor, DepositMinor: s.DepositMinor, Currency: strings.ToUpper(s.Currency), Active: s.Active}
	if t := s.TakeHome; t != nil {
		o.TakeHome = domain.TakeHome{CommissionPercent: float64(t.CommissionBps) / 100, CommissionMinor: t.CommissionMinor,
			CardFeesMinor: t.CardFeesMinor, TakeHomeMinor: t.TakeHomeMinor}
	}
	return o
}

func serviceRequest(in domain.ServiceInput, current *bkService) map[string]any {
	body := map[string]any{"name": strings.TrimSpace(in.Name), "description": in.Description, "durationMinutes": in.DurationMinutes,
		"priceMinor": in.PriceMinor, "depositMinor": in.DepositMinor, "modes": []string{"IN_PERSON"}}
	if current != nil {
		if len(current.Modes) > 0 {
			body["modes"] = current.Modes
		}
		if current.Category != "" {
			body["category"] = current.Category
		}
		if current.AddOns != nil {
			body["addOns"] = current.AddOns
		}
	}
	return body
}

func (b *bookingsImpl) Services(ctx context.Context, bearer string, p port.ProviderRef) ([]domain.ServiceOffer, error) {
	var list []bkService
	if _, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/services"), Bearer: bearer}, &list); err != nil {
		return nil, err
	}
	out := make([]domain.ServiceOffer, 0, len(list))
	for _, s := range list {
		out = append(out, s.offer())
	}
	return out, nil
}

// setActive moves a service to the wanted state with activate / deactivate.
func (b *bookingsImpl) setActive(ctx context.Context, bearer string, p port.ProviderRef, s bkService, active bool) (bkService, error) {
	if s.Active == active {
		return s, nil
	}
	seg := "/deactivate"
	if active {
		seg = "/activate"
	}
	var out bkService
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: providerPath(p, "/services/"+url.PathEscape(s.ID)+seg),
		Bearer: bearer}, &out)
	return out, err
}

func (b *bookingsImpl) CreateService(ctx context.Context, bearer string, p port.ProviderRef, in domain.ServiceInput) (domain.ServiceOffer, error) {
	var created bkService
	if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: providerPath(p, "/services"), Bearer: bearer,
		JSON: serviceRequest(in, nil)}, &created); err != nil {
		return domain.ServiceOffer{}, err
	}
	out, err := b.setActive(ctx, bearer, p, created, in.Active)
	return out.offer(), err
}

func (b *bookingsImpl) UpdateService(ctx context.Context, bearer string, p port.ProviderRef, id string, in domain.ServiceInput) (domain.ServiceOffer, error) {
	path := providerPath(p, "/services/"+url.PathEscape(id))
	var current bkService
	if _, err := call(ctx, b.c, httpclient.Request{Path: path, Bearer: bearer}, &current); err != nil {
		return domain.ServiceOffer{}, err
	}
	var updated bkService
	if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: path, Bearer: bearer,
		JSON: serviceRequest(in, &current)}, &updated); err != nil {
		return domain.ServiceOffer{}, err
	}
	out, err := b.setActive(ctx, bearer, p, updated, in.Active)
	return out.offer(), err
}

// ---- cancellation policy

func (b *bookingsImpl) Policy(ctx context.Context, bearer string, p port.ProviderRef) (domain.PolicyInput, error) {
	set, err := b.settings(ctx, bearer, p)
	if err != nil {
		return domain.PolicyInput{}, err
	}
	if set.Policy == nil {
		return domain.PolicyInput{}, nil
	}
	pol := set.Policy
	in := domain.PolicyInput{Kind: strings.ToUpper(pol.Kind), FreeHours: pol.FreeHours, NoShow: firstNonEmptyStr(pol.NoShow, "DEPOSIT"),
		RescheduleHours: pol.RescheduleHours, MaxReschedules: pol.MaxReschedules}
	for _, t := range pol.Tiers {
		if t.ShareBps > 0 && t.ShareBps < 10_000 {
			in.PartialRefund, in.PartialFromHours, in.PartialPercent = true, t.HoursBefore, t.ShareBps/100
		}
	}
	return in, nil
}

func (b *bookingsImpl) SavePolicy(ctx context.Context, bearer string, p port.ProviderRef, in domain.PolicyInput) (domain.PolicyInput, error) {
	body := map[string]any{"kind": in.Kind}
	if in.Kind == "CUSTOM" {
		body["custom"] = map[string]any{"freeHours": in.FreeHours, "partialRefund": in.PartialRefund,
			"partialFromHours": in.PartialFromHours, "partialPercent": in.PartialPercent, "noShow": in.NoShow,
			"rescheduleHours": in.RescheduleHours, "maxReschedules": in.MaxReschedules}
	}
	if _, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: providerPath(p, "/cancellation-policy"),
		Bearer: bearer, JSON: body}, nil); err != nil {
		return domain.PolicyInput{}, err
	}
	return b.Policy(ctx, bearer, p)
}

// ---- earnings and dashboard analytics

type bkEarnings struct {
	Currency            string `json:"currency"`
	DepositsOnHoldMinor int64  `json:"depositsOnHoldMinor"`
	Rows                []struct {
		BookingID        string    `json:"bookingId"`
		Reference        string    `json:"reference"`
		Customer         string    `json:"customer"`
		StartAt          time.Time `json:"startAt"`
		ServiceName      string    `json:"serviceName"`
		Status           string    `json:"status"`
		HeldMinor        int64     `json:"heldMinor"`
		CollectedMinor   int64     `json:"collectedMinor"`
		CommissionBps    int       `json:"commissionBps"`
		CommissionMinor  int64     `json:"commissionMinor"`
		CardFeesMinor    int64     `json:"cardFeesMinor"`
		ProviderFeeMinor int64     `json:"providerFeeMinor"`
		NetMinor         int64     `json:"netMinor"`
		BalanceDueMinor  int64     `json:"balanceDueMinor"`
	} `json:"rows"`
}

func (b *bookingsImpl) earnings(ctx context.Context, bearer string, p port.ProviderRef, from, to time.Time) (bkEarnings, error) {
	var e bkEarnings
	q := url.Values{"from": {from.UTC().Format(time.RFC3339)}, "to": {to.UTC().Format(time.RFC3339)}}
	_, err := call(ctx, b.c, httpclient.Request{Path: providerPath(p, "/earnings"), Query: q, Bearer: bearer}, &e)
	return e, err
}

func (b *bookingsImpl) Earnings(ctx context.Context, bearer string, p port.ProviderRef, from, to time.Time) (port.BookingsEarnings, error) {
	e, err := b.earnings(ctx, bearer, p, from, to.AddDate(0, 0, 1))
	if err != nil {
		return port.BookingsEarnings{}, err
	}
	out := port.BookingsEarnings{Currency: strings.ToUpper(e.Currency), HeldMinor: e.DepositsOnHoldMinor, Rows: []domain.EarningsRow{}}
	for _, r := range e.Rows {
		if out.CommissionPercent == 0 && r.CommissionBps > 0 {
			out.CommissionPercent = float64(r.CommissionBps) / 100
		}
		out.Rows = append(out.Rows, domain.EarningsRow{BookingID: r.BookingID, Reference: r.Reference, CustomerName: r.Customer,
			ServiceName: r.ServiceName, StartAt: r.StartAt, Status: r.Status, CollectedMinor: r.CollectedMinor,
			CommissionMinor: r.CommissionMinor, CardFeesMinor: r.CardFeesMinor, LateCancelFeeMinor: r.ProviderFeeMinor})
	}
	return out, nil
}

// Analytics derives the provider dashboard from bookings in [from, to] (per day, per service, per status) and the
// earnings rows (paid out = net of completed bookings, pending = what is still held, per ISO week).
func (b *bookingsImpl) Analytics(ctx context.Context, bearer string, p port.ProviderRef, from, to time.Time) (port.BookingsAnalytics, error) {
	list, err := b.bookings(ctx, bearer, p, from, to.AddDate(0, 0, 1), "")
	if err != nil {
		return port.BookingsAnalytics{}, err
	}
	e, err := b.earnings(ctx, bearer, p, from, to.AddDate(0, 0, 1))
	if err != nil {
		return port.BookingsAnalytics{}, err
	}
	var out port.BookingsAnalytics
	days := map[string]int{}
	byService, byStatus := map[string]int{}, map[string]int{}
	for d := from; !d.After(to); d = d.AddDate(0, 0, 1) {
		key := d.Format(time.DateOnly)
		days[key] = len(out.Days)
		out.Days = append(out.Days, struct {
			Date         string `json:"date"`
			Bookings     int    `json:"bookings"`
			DepositMinor int64  `json:"depositMinor"`
		}{Date: key})
	}
	for _, bk := range list {
		if i, ok := days[bk.StartAt.In(from.Location()).Format(time.DateOnly)]; ok {
			out.Days[i].Bookings++
			out.Days[i].DepositMinor += bk.Service.DepositMinor
		}
		byService[bk.Service.Name]++
		byStatus[domain.BookingStatusLabel(bk.Status)]++
	}
	for _, name := range sortedKeys(byService) {
		out.ByService = append(out.ByService, struct {
			Service  string `json:"service"`
			Bookings int    `json:"bookings"`
		}{name, byService[name]})
	}
	for _, name := range sortedKeys(byStatus) {
		out.ByStatus = append(out.ByStatus, struct {
			Status string `json:"status"`
			Count  int    `json:"count"`
		}{name, byStatus[name]})
	}
	weeks := map[string]*struct{ paid, pending int64 }{}
	for _, r := range e.Rows {
		y, w := r.StartAt.ISOWeek()
		key := fmt.Sprintf("%d-W%02d", y, w)
		if weeks[key] == nil {
			weeks[key] = &struct{ paid, pending int64 }{}
		}
		if r.Status == "COMPLETED" {
			weeks[key].paid += r.NetMinor
		}
		weeks[key].pending += r.HeldMinor + r.BalanceDueMinor
	}
	keys := make([]string, 0, len(weeks))
	for k := range weeks {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		out.Weeks = append(out.Weeks, struct {
			Week         string `json:"week"`
			PaidOutMinor int64  `json:"paidOutMinor"`
			PendingMinor int64  `json:"pendingMinor"`
		}{k, weeks[k].paid, weeks[k].pending})
	}
	return out, nil
}

func sortedKeys(m map[string]int) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if m[keys[i]] != m[keys[j]] {
			return m[keys[i]] > m[keys[j]]
		}
		return keys[i] < keys[j]
	})
	return keys
}
