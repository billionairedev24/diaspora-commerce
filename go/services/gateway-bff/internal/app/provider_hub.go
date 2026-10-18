package app

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ProviderHubService serves the Provider Hub tools inside the portal: calendar with day summaries, booking requests
// and actions, working hours, services, cancellation policy and earnings (bookings, planned), and the public page

type ProviderHubService interface {
	Calendar(ctx context.Context, c *domain.Caller, from, to string) (domain.CalendarView, error)
	Requests(ctx context.Context, c *domain.Caller) ([]domain.BookingCard, error)
	Act(ctx context.Context, c *domain.Caller, idempotencyKey, bookingID, action, reason string, proposedStart *time.Time) (domain.BookingCard, error)
	Hours(ctx context.Context, c *domain.Caller) (domain.Availability, error)
	SaveHours(ctx context.Context, c *domain.Caller, a domain.Availability) (domain.Availability, error)
	Services(ctx context.Context, c *domain.Caller) ([]domain.ServiceOffer, error)
	CreateService(ctx context.Context, c *domain.Caller, in domain.ServiceInput) (domain.ServiceOffer, error)
	UpdateService(ctx context.Context, c *domain.Caller, id string, in domain.ServiceInput) (domain.ServiceOffer, error)
	Policy(ctx context.Context, c *domain.Caller) (domain.CancellationPolicy, error)
	SavePolicy(ctx context.Context, c *domain.Caller, in domain.PolicyInput) (domain.CancellationPolicy, error)
	Earnings(ctx context.Context, c *domain.Caller, from, to string) (domain.Earnings, error)
}

type providerHubImpl struct{ r *orgResolver }

var _ ProviderHubService = (*providerHubImpl)(nil)

// NewProviderHubService returns the ProviderHubService.
func NewProviderHubService(d SellerDeps) ProviderHubService {
	return &providerHubImpl{r: newResolver(d)}
}

const kindProvider = domain.OrgKindProvider

func (p *providerHubImpl) now() time.Time { return p.r.d.Clock.now() }

// dates parses an inclusive date range (at most maxDays long).
func (p *providerHubImpl) dates(from, to string, maxDays int, defaultDays int) (time.Time, time.Time, error) {
	loc := p.r.d.Location
	today := p.now().In(loc)
	start := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, loc)
	end := start.AddDate(0, 0, defaultDays-1)
	if from == "" && to == "" && defaultDays < 0 {
		end = start
		start = start.AddDate(0, 0, defaultDays+1)
	}
	errs := map[string]string{}
	if from != "" {
		t, err := time.ParseInLocation(time.DateOnly, from, loc)
		if err != nil {
			errs["from"] = "Use YYYY-MM-DD"
		}
		start = t
	}
	if to != "" {
		t, err := time.ParseInLocation(time.DateOnly, to, loc)
		if err != nil {
			errs["to"] = "Use YYYY-MM-DD"
		}
		end = t
	}
	if len(errs) == 0 && (end.Before(start) || end.Sub(start) > time.Duration(maxDays)*24*time.Hour) {
		errs["to"] = fmt.Sprintf("Choose a range of up to %d days", maxDays)
	}
	if len(errs) > 0 {
		return start, end, domain.ValidationProblem(errs)
	}
	return start, end, nil
}

func (p *providerHubImpl) normalize(cards []domain.BookingCard) []domain.BookingCard {
	now := p.now()
	out := make([]domain.BookingCard, 0, len(cards))
	for _, b := range cards {
		b.Normalize(now)
		out = append(out, b)
	}
	slices.SortStableFunc(out, func(a, b domain.BookingCard) int { return a.StartAt.Compare(b.StartAt) })
	return out
}

func fullWeek(in []domain.DayHours) []domain.DayHours {
	by := map[string]domain.DayHours{}
	for _, d := range in {
		by[strings.ToUpper(d.Day)] = d
	}
	out := make([]domain.DayHours, 0, 7)
	for _, d := range domain.Weekdays {
		h := by[d]
		h.Day = d
		if h.Ranges == nil {
			h.Ranges = []domain.TimeRange{}
		}
		out = append(out, h)
	}
	return out
}

func (p *providerHubImpl) Calendar(ctx context.Context, c *domain.Caller, from, to string) (domain.CalendarView, error) {
	if from == "" || to == "" {
		return domain.CalendarView{}, domain.ValidationProblem(map[string]string{"from": "from and to are required"})
	}
	start, end, err := p.dates(from, to, 42, 7)
	if err != nil {
		return domain.CalendarView{}, err
	}
	sc, err := p.r.require(ctx, c, kindProvider, "calendar", domain.LevelView)
	if err != nil {
		return domain.CalendarView{}, err
	}
	cal, err := p.r.d.Bookings.Calendar(ctx, c.BearerToken, sc.provider(), start, end)
	if err != nil {
		return domain.CalendarView{}, mapErr("bookings", err)
	}
	tz := firstNonEmpty(cal.Timezone, p.r.d.Location.String())
	loc, lerr := time.LoadLocation(tz)
	if lerr != nil {
		loc = p.r.d.Location
	}
	bookings := p.normalize(cal.Bookings)
	return domain.CalendarView{Timezone: tz, From: start.Format(time.DateOnly), To: end.Format(time.DateOnly), OpenHours: fullWeek(cal.OpenHours),
		Bookings: bookings, Days: domain.DaySummaries(bookings, start, end, loc)}, nil
}

func (p *providerHubImpl) Requests(ctx context.Context, c *domain.Caller) ([]domain.BookingCard, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "requests", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := p.r.d.Bookings.Requests(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return nil, mapErr("bookings", err)
	}
	out := p.normalize(list)
	// "You keep $X after the 8% connection fee": the provider sees its take-home before accepting.
	bookingTakeHomes(ctx, p.r.d.Fees, sc.org.ID, p.now(), out)
	return out, nil
}

func (p *providerHubImpl) Act(ctx context.Context, c *domain.Caller, key, id, action, reason string, proposed *time.Time) (domain.BookingCard, error) {
	errs := map[string]string{}
	switch {
	case !slices.Contains(domain.BookingActions, action):
		errs["action"] = "Unknown action"
	case (action == "DECLINE" || action == "CANCEL") && strings.TrimSpace(reason) == "":
		errs["reason"] = "Tell the customer why"
	case action == "PROPOSE" && (proposed == nil || !proposed.After(p.now())):
		errs["proposedStartAt"] = "Choose a future time"
	}
	if len([]rune(reason)) > 500 {
		errs["reason"] = "At most 500 characters"
	}
	if len(errs) > 0 {
		return domain.BookingCard{}, domain.ValidationProblem(errs)
	}
	if _, err := p.r.require(ctx, c, kindProvider, "requests", domain.LevelEdit); err != nil {
		return domain.BookingCard{}, err
	}
	b, err := p.r.d.Bookings.Act(ctx, c.BearerToken, key, id, action, strings.TrimSpace(reason), proposed)
	if err != nil {
		return domain.BookingCard{}, mapErr("bookings", err)
	}
	b.Normalize(p.now())
	return b, nil
}

func (p *providerHubImpl) availability(a domain.Availability) domain.Availability {
	a.Weekly = fullWeek(a.Weekly)
	if a.BlockedDates == nil {
		a.BlockedDates = []domain.BlockedDate{}
	}
	if a.Timezone == "" {
		a.Timezone = p.r.d.Location.String()
	}
	return a
}

func (p *providerHubImpl) Hours(ctx context.Context, c *domain.Caller) (domain.Availability, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "hours", domain.LevelView)
	if err != nil {
		return domain.Availability{}, err
	}
	a, err := p.r.d.Bookings.Availability(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return domain.Availability{}, mapErr("bookings", err)
	}
	return p.availability(a), nil
}

func (p *providerHubImpl) SaveHours(ctx context.Context, c *domain.Caller, a domain.Availability) (domain.Availability, error) {
	if errs := a.Validate(); len(errs) > 0 {
		return domain.Availability{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "hours", domain.LevelEdit)
	if err != nil {
		return domain.Availability{}, err
	}
	out, err := p.r.d.Bookings.SaveAvailability(ctx, c.BearerToken, sc.provider(), p.availability(a))
	if err != nil {
		return domain.Availability{}, mapErr("bookings", err)
	}
	return p.availability(out), nil
}

func serviceOf(s domain.ServiceOffer) domain.ServiceOffer {
	if s.Currency == "" {
		s.Currency = domain.Currency
	}
	return s
}

func (p *providerHubImpl) Services(ctx context.Context, c *domain.Caller) ([]domain.ServiceOffer, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := p.r.d.Bookings.Services(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return nil, mapErr("bookings", err)
	}
	out := make([]domain.ServiceOffer, 0, len(list))
	for _, s := range list {
		out = append(out, serviceOf(s))
	}
	return out, nil
}

func (p *providerHubImpl) CreateService(ctx context.Context, c *domain.Caller, in domain.ServiceInput) (domain.ServiceOffer, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ServiceOffer{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelEdit)
	if err != nil {
		return domain.ServiceOffer{}, err
	}
	s, err := p.r.d.Bookings.CreateService(ctx, c.BearerToken, sc.provider(), in)
	if err != nil {
		return domain.ServiceOffer{}, mapErr("bookings", err)
	}
	return serviceOf(s), nil
}

func (p *providerHubImpl) UpdateService(ctx context.Context, c *domain.Caller, id string, in domain.ServiceInput) (domain.ServiceOffer, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ServiceOffer{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "services", domain.LevelEdit)
	if err != nil {
		return domain.ServiceOffer{}, err
	}
	s, err := p.r.d.Bookings.UpdateService(ctx, c.BearerToken, sc.provider(), id, in)
	if err != nil {
		return domain.ServiceOffer{}, mapErr("bookings", err)
	}
	return serviceOf(s), nil
}

func (p *providerHubImpl) Policy(ctx context.Context, c *domain.Caller) (domain.CancellationPolicy, error) {
	sc, err := p.r.require(ctx, c, kindProvider, "policy", domain.LevelView)
	if err != nil {
		return domain.CancellationPolicy{}, err
	}
	pol, err := p.r.d.Bookings.Policy(ctx, c.BearerToken, sc.provider())
	if err != nil {
		return domain.CancellationPolicy{}, mapErr("bookings", err)
	}
	pol.Kind = firstNonEmpty(strings.ToUpper(pol.Kind), "MODERATE")
	return pol.Describe(), nil
}

func (p *providerHubImpl) SavePolicy(ctx context.Context, c *domain.Caller, in domain.PolicyInput) (domain.CancellationPolicy, error) {
	if preset, ok := domain.PresetPolicy(in.Kind); ok {
		in = preset
	}
	if errs := in.Validate(); len(errs) > 0 {
		return domain.CancellationPolicy{}, domain.ValidationProblem(errs)
	}
	sc, err := p.r.require(ctx, c, kindProvider, "policy", domain.LevelEdit)
	if err != nil {
		return domain.CancellationPolicy{}, err
	}
	out, err := p.r.d.Bookings.SavePolicy(ctx, c.BearerToken, sc.provider(), in)
	if err != nil {
		return domain.CancellationPolicy{}, mapErr("bookings", err)
	}
	if out.Kind == "" {
		out = in
	}
	return out.Describe(), nil
}

func (p *providerHubImpl) Earnings(ctx context.Context, c *domain.Caller, from, to string) (domain.Earnings, error) {
	start, end, err := p.dates(from, to, 366, -30)
	if err != nil {
		return domain.Earnings{}, err
	}
	sc, err := p.r.require(ctx, c, kindProvider, "earnings", domain.LevelView)
	if err != nil {
		return domain.Earnings{}, err
	}
	var (
		wg      sync.WaitGroup
		e       port.BookingsEarnings
		eErr    error
		fees    []port.ProviderFeeResponse
		feesErr error
	)
	wg.Go(func() { e, eErr = p.r.d.Bookings.Earnings(ctx, c.BearerToken, sc.provider(), start, end) })
	wg.Go(func() { fees, feesErr = p.r.d.Payouts.ProviderFees(ctx, c.BearerToken, sc.org.ID) })
	var connection *domain.SellerRateCard
	wg.Go(func() { connection = rateCardOf(ctx, p.r.d.Fees, sc.org.ID, domain.FeeKindService, time.Time{}) })
	wg.Wait()
	if eErr != nil {
		return domain.Earnings{}, mapErr("bookings", eErr)
	}
	out := domain.Earnings{Currency: firstNonEmpty(e.Currency, domain.Currency), CommissionPercent: e.CommissionPercent, PayoutDay: "Tuesday",
		Totals: domain.EarningsTotals{HeldMinor: e.HeldMinor}, Rows: e.Rows}
	if out.Rows == nil {
		out.Rows = []domain.EarningsRow{}
	}
	out.Total()
	out.ProviderFees = providerFeesOf(fees, feesErr == nil)
	out.ConnectionFee = connection
	return out, nil
}

// providerFeesOf sums outstanding and collected provider fees (payments), newest first as payments lists them.
func providerFeesOf(list []port.ProviderFeeResponse, ok bool) domain.ProviderFees {
	out := domain.ProviderFees{Available: ok, Items: []domain.ProviderFee{}}
	for _, f := range list {
		st := strings.ToUpper(f.Status)
		if st != "COLLECTED" {
			st = "OUTSTANDING"
			out.OutstandingMinor += f.AmountMinor
		} else {
			out.CollectedMinor += f.AmountMinor
		}
		out.Items = append(out.Items, domain.ProviderFee{ID: f.ID, BookingReference: firstNonEmpty(f.BookingReference, f.BookingID), AmountMinor: f.AmountMinor,
			Currency: firstNonEmpty(strings.ToUpper(f.Currency), domain.Currency), Reason: firstNonEmpty(f.Reason, "Late cancellation by provider"),
			Status: st, NextAttemptAt: f.NextAttemptAt, CreatedAt: f.CreatedAt})
	}
	return out
}
